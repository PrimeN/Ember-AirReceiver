// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Surface
import app.ember.airreceiver.AirReceiverActivity
import app.ember.airreceiver.airplay.nowplaying.NowPlayingInfo
import app.ember.airreceiver.media.MediaCapabilities
import app.ember.airreceiver.settings.SettingsRepository
import app.ember.airreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.ember.airreceiver.airplay.playback.PlaybackInfo

/**
 * AirReceiverService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that Ember AirReceiver is active.
 *
 * HOW: Bind to this service from [AirReceiverActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * The service is a thin coordinator — the work is delegated to four focused helpers:
 *   • [ServiceStateBus]        — owns the published state flows
 *   • [ReceiverSupervisor]     — owns the four protocol receivers
 *   • [ServiceNotificationManager] — owns the foreground notification
 *   • [NetworkRecoveryMonitor] — re-advertises over mDNS after a network outage
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class AirReceiverService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — the flows themselves live in the state bus, this class publishes them.
    private val stateBus = ServiceStateBus()

    // Local aliases for the shared state flows (the service code below uses the original names).
    private val _serviceState = stateBus.serviceStateFlow
    private val _activeConnection = stateBus.activeConnectionFlow

    val serviceState: StateFlow<ServiceState> = stateBus.serviceState
    val airPlayState: StateFlow<ProtocolState> = stateBus.airPlayState
    val miracastState: StateFlow<ProtocolState> = stateBus.miracastState
    val castState: StateFlow<ProtocolState> = stateBus.castState
    val dlnaState: StateFlow<ProtocolState> = stateBus.dlnaState
    val activeConnection: StateFlow<ActiveConnection?> = stateBus.activeConnection
    val photoFrame: StateFlow<PhotoFrame?> = stateBus.photoFrame
    val nowPlaying: StateFlow<NowPlayingInfo?> = stateBus.nowPlaying
    val pairingPin: StateFlow<String?> = stateBus.pairingPin
    val lastAirPlaySession: StateFlow<String?> = stateBus.lastAirPlaySession

    // Surface provider — supplied by AirReceiverActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: (() -> Surface?)? = null

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    // Foreground notification — channel, status text and the Stop/Restart actions.
    private lateinit var notifications: ServiceNotificationManager

    // Protocol receivers — AirPlay, Miracast, Cast and DLNA.
    private lateinit var receivers: ReceiverSupervisor

    // Network monitor — re-advertises over mDNS after a network outage.
    private lateinit var mediaControls: EmberMediaSession
    private val urlPlayback = MutableStateFlow<PlaybackInfo?>(null)
    internal val airPlayUrlPlayback = urlPlayback.asStateFlow()
    private lateinit var networkRecovery: NetworkRecoveryMonitor

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("AirReceiverService created")
        settingsRepository = SettingsRepository(applicationContext)
        notifications = ServiceNotificationManager(applicationContext)
        receivers = ReceiverSupervisor(
            context = applicationContext,
            states = stateBus,
            notifications = notifications,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            surfaceProvider = { videoSurfaceProvider?.invoke() },
            bringToFront = { bringStreamingScreenToFront() }
        )
        networkRecovery = NetworkRecoveryMonitor(
            context = applicationContext,
            isServiceRunning = { stateBus.serviceState.value == ServiceState.Running },
            onNetworkRestored = {
                if (LocalNetworkAccess.hasAccess(this)) receivers.readvertiseAll()
                else serviceScope.launch { startReceivers() }
            }
        )
        mediaControls = EmberMediaSession(this, ::setAirPlayUrlRate, ::seekAirPlayUrl, ::stopAirPlayUrl, ::sendAirPlayRemoteCommand)
        serviceScope.launch {
            while (isActive) {
                val info = receivers.airPlayUrlPlayback()
                urlPlayback.value = info
                val canControl = receivers.canControlAirPlaySender()
                withContext(Dispatchers.Main) {
                    mediaControls.update(info, nowPlaying.value, canControl)
                }
                delay(350)
            }
        }
        notifications.createNotificationChannel()
        networkRecovery.registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, notifications.buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents. Cleanly stop all receivers (which closes the RTSP
     * connection so an active mirror ends on the sender too) and stop the service — don't let
     * START_STICKY silently resurrect it as a zombie that keeps advertising/streaming invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — stopping receivers + service")
        stopReceivers()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [AirReceiverActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [ReceiverSupervisor.startAirPlay]. The lambda should
     * return null if the Activity's StreamingScreen is not yet available (e.g., surface not yet
     * created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        videoSurfaceProvider = provider
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        receivers.sendAirPlayRemoteCommand(command)
    }

    internal fun selectUrlTrack(session: Long, kind: app.ember.airreceiver.airplay.playback.PlaybackTrackKind, index: Int?) {
        serviceScope.launch { receivers.selectUrlTrack(session, kind, index) }
    }

    internal fun setAirPlayUrlRate(rate: Float) {
        serviceScope.launch { receivers.setAirPlayUrlRate(rate) }
    }

    internal fun seekAirPlayUrl(seconds: Double) {
        serviceScope.launch { receivers.seekAirPlayUrl(seconds) }
    }

    internal fun stopAirPlayUrl() {
        serviceScope.launch { receivers.stopAirPlayUrl() }
    }

    /**
     * Sends a DAVP transport command (TV remote → DLNA controller), e.g. play/pause or skip.
     * Bound Activities call this from media-key events.
     */
    fun sendDlnaRemoteCommand(command: String) {
        receivers.sendDlnaRemoteCommand(command)
    }

    override fun onDestroy() {
        Logger.i("AirReceiverService destroying")
        networkRecovery.unregisterNetworkCallback()
        receivers.stopAllReceiversInternal()
        serviceJob.cancel()
        mediaControls.release()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = settingsRepository.settingsFlow.first()
        Logger.i(
            "Starting receivers: AirPlay=${settings.airPlayEnabled}, " +
                "Miracast=${settings.miracastEnabled}, Cast=${settings.castEnabled}, " +
                "DLNA=${settings.dlnaEnabled}"
        )
        if (!LocalNetworkAccess.hasAccess(this)) {
            receivers.stopAllReceiversInternal()
            fun required(enabled: Boolean) = if (enabled) ProtocolState.PERMISSION_REQUIRED else ProtocolState.DISABLED
            stateBus.airPlayStateFlow.value = required(settings.airPlayEnabled)
            stateBus.miracastStateFlow.value = required(settings.miracastEnabled)
            stateBus.castStateFlow.value = required(settings.castEnabled && app.ember.airreceiver.BuildConfig.FLAVOR == "googletv")
            stateBus.dlnaStateFlow.value = required(settings.dlnaEnabled)
            _serviceState.value = ServiceState.Stopped
            notifications.updateNotification(isRunning = false)
            return
        }
        logMediaCapabilities()

        _serviceState.value = ServiceState.Running
        notifications.updateNotification(isRunning = true)

        if (settings.airPlayEnabled)   receivers.startAirPlay(settings)
        if (settings.miracastEnabled)  receivers.startMiracast()
        if (settings.castEnabled)      receivers.startCast()
        if (settings.dlnaEnabled)      receivers.startDlna(settings)
    }

    private fun logMediaCapabilities() {
        val capabilities = MediaCapabilities.detect(applicationContext)
        Logger.i(
            "Media capabilities: video=${capabilities.video}, audio=${capabilities.audio}, " +
                "passthrough=${capabilities.passthrough}"
        )
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        receivers.stopAllReceiversInternal()
        _serviceState.value = ServiceState.Stopped
        _activeConnection.value = null
        notifications.updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ServiceState.Restarting
        notifications.updateNotification(isRunning = false)
        receivers.stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    /**
     * Opens [AirReceiverActivity] when a session connects while the app is in the background, so the
     * StreamingScreen Surface exists before MirrorStreamServer.awaitSurface() gives up (5 s).
     * Without this, a mirroring session started from the TV home screen plays audio only, and
     * opening the app later stays black until the sender's next IDR frame.
     *
     * Android 10+ only allows this background start when "Display over other apps"
     * (SYSTEM_ALERT_WINDOW) is granted; otherwise this logs and does nothing.
     */
    private fun bringStreamingScreenToFront() {
        if (videoSurfaceProvider?.invoke() != null) return          // already on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            Logger.w("Sender connected in background; grant 'Display over other apps' to show video automatically")
            return
        }
        val intent = Intent(this, AirReceiverActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching { startActivity(intent) }
            .onFailure { Logger.e("Could not bring AirReceiverActivity to front", it) }
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [AirReceiverService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): AirReceiverService = this@AirReceiverService
    }

    companion object {
        const val CHANNEL_ID      = "airreceiver_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "app.ember.airreceiver.action.START"
        const val ACTION_STOP     = "app.ember.airreceiver.action.STOP"
        const val ACTION_RESTART  = "app.ember.airreceiver.action.RESTART"
    }
}
