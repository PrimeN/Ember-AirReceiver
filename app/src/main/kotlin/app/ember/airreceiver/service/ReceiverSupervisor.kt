// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.service

import android.content.Context
import android.view.Surface
import app.ember.airreceiver.airplay.AirPlayReceiver
import app.ember.airreceiver.cast.CastReceiver
import app.ember.airreceiver.dlna.DlnaReceiver
import app.ember.airreceiver.miracast.MiracastReceiver
import app.ember.airreceiver.settings.AppSettings
import app.ember.airreceiver.util.LogRedactor
import app.ember.airreceiver.util.Logger

/**
 * ReceiverSupervisor — Owns the four protocol receivers and their start/stop lifecycle.
 *
 * WHAT: Creates the AirPlay, Miracast, Cast and DLNA receivers, starts them, stops them
 * and translates their callbacks into the published service state (protocol state,
 * active connection, photo, now-playing info, pairing PIN) and the notification text.
 *
 * WHY: [AirReceiverService] used to do all of this itself, which made the service class
 * grow past the 400-line limit of CONTRIBUTING.md and mixed two unrelated concerns: the
 * Android service lifecycle and the lifecycle of four independent protocols. The
 * supervisor keeps the protocol-specific wiring in one place, so the service only has to
 * say "start" and "stop".
 *
 * HOW: The service creates one supervisor in `onCreate()` and calls [startAirPlay],
 * [startMiracast], [startCast] and [startDlna] for the protocols that are enabled in the
 * settings. [stopAllReceiversInternal] releases every receiver again. The supervisor
 * publishes into the shared [ServiceStateBus] and writes the notification through
 * [ServiceNotificationManager].
 *
 * Example:
 *   val supervisor = ReceiverSupervisor(context, stateBus, notifications, surfaceProvider) { bringToFront() }
 *   supervisor.startAirPlay(settings)
 *   supervisor.stopAllReceiversInternal()
 */
internal class ReceiverSupervisor(
    /** Application context — the receivers outlive the Activity that started them. */
    private val context: Context,
    /** Shared state holder the receivers publish into. */
    private val states: ServiceStateBus,
    /** Notification writer for the "now streaming from …" status line. */
    private val notifications: ServiceNotificationManager,
    /** Lazy provider for the video Surface — supplied by the bound Activity. */
    private val surfaceProvider: () -> Surface?,
    /** Brings the streaming screen to the front — the service owns the Activity intent. */
    private val bringToFront: () -> Unit
) {

    // Local aliases for the shared state flows. The receiver code below is unchanged;
    // it only publishes into the bus instead of into the service.
    private val _airPlayState = states.airPlayStateFlow
    private val _miracastState = states.miracastStateFlow
    private val _castState = states.castStateFlow
    private val _dlnaState = states.dlnaStateFlow
    private val _activeConnection = states.activeConnectionFlow
    private val _photoFrame = states.photoFrameFlow
    private val _nowPlaying = states.nowPlayingFlow
    private val _pairingPin = states.pairingPinFlow

    // Receiver instances — null when not running
    @Volatile private var airPlayReceiver: AirPlayReceiver? = null
    @Volatile private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null
    @Volatile private var dlnaReceiver: DlnaReceiver? = null

    @Volatile private var preferences = AppSettings.DEFAULT

    internal fun updatePreferences(settings: AppSettings) {
        preferences = settings
        ownership.allowReplacement = settings.replaceCurrentSender
    }

    private fun mirrorFormat() = app.ember.airreceiver.airplay.mirror.MirrorCapabilities.resolve(context, preferences)

    private val ownership = PlaybackOwnership.shared
    internal val audioApproval = AudioModeApproval()
    private val generations = java.util.concurrent.ConcurrentHashMap<Protocol, Any>()

    internal val hasReceivers: Boolean get() = generations.isNotEmpty()

    private var closed = false

    @Synchronized
    internal fun start(protocol: Protocol, settings: AppSettings) {
        if (closed) return
        if (protocol == Protocol.CAST && app.ember.airreceiver.BuildConfig.FLAVOR != "googletv") return
        if (generations.containsKey(protocol)) {
            if (states.protocolState(protocol).value !in setOf(ProtocolState.ERROR, ProtocolState.PERMISSION_REQUIRED)) return
            stop(protocol)
        }
        states.protocolState(protocol).value = ProtocolState.STARTING
        try {
            when (protocol) {
                Protocol.AIRPLAY -> startAirPlay(settings)
                Protocol.MIRACAST -> startMiracast()
                Protocol.CAST -> startCast()
                Protocol.DLNA -> startDlna(settings)
            }
        } catch (e: Exception) {
            stop(protocol)
            states.protocolState(protocol).value = ProtocolState.ERROR
            Logger.e("Could not start ${protocol.name}", e)
        }
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [app.ember.airreceiver.airplay.discovery.MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [app.ember.airreceiver.AirReceiverActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // WHY the name is registered here: the display name is the name the owner gave
        // their television, and it must not appear in a log excerpt that may end up in a
        // public issue tracker. The call sites do not log it — this is the second line
        // of defence, and it belongs where the name is known.
        LogRedactor.registerPersonalString(settings.effectiveDisplayName)
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        app.ember.airreceiver.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        // Whether the session that just started carries video (see onVideoSessionChanged).
        var pendingHasVideo = false

        val owner = Any()
        generations[Protocol.AIRPLAY] = owner
        airPlayReceiver = AirPlayReceiver(
            context = context,
            admitPlayback = { admit(Protocol.AIRPLAY, owner) { airPlayReceiver?.disconnectSender() } },
            releasePlayback = { ownership.release(owner) },
            approveAudioOnly = { sender -> preferences.autoAudioMode || audioApproval.ask(sender) },
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled || settings.airPlayAudioOnly,
            audioOnlyMode = settings.airPlayAudioOnly,
            lowBandwidthMirroring = settings.lowBandwidthMirroring,
            pinAuthEnabled = settings.effectivePinPolicy != app.ember.airreceiver.settings.AirPlayPinPolicy.OFF,
            preferredPinPolicy = { preferences.effectivePinPolicy },
            preferredMirrorFormat = ::mirrorFormat,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When AirReceiverActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = surfaceProvider,
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            // Whether the session that just started carries video. Only a video session
            // needs the streaming Surface, so only a video session may pull the app to
            // the front — an audio-only session (such as a music or podcast stream) must not
            // interrupt whatever the user is watching on the TV.
            onVideoSessionChanged = { hasVideo ->
                pendingHasVideo = hasVideo
            },
            onPhotoReceived = onPhotoReceived@{ bytes, imageType ->
                if (generations[Protocol.AIRPLAY] !== owner) return@onPhotoReceived
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType, protocol = Protocol.AIRPLAY
                )
                notifications.updateNotification(isRunning = true)
            },
            onPhotoCleared = onPhotoCleared@{
                if (generations[Protocol.AIRPLAY] !== owner) return@onPhotoCleared
                clearPhoto(Protocol.AIRPLAY)
            },
            onNowPlayingChanged = onNowPlayingChanged@{ info ->
                if (generations[Protocol.AIRPLAY] !== owner) return@onNowPlayingChanged
                _nowPlaying.value = info
            },
            onSessionSummary = { summary ->
                states.lastAirPlaySessionFlow.value = summary
            },
            onPinChanged = onPinChanged@{ pin ->
                if (generations[Protocol.AIRPLAY] !== owner) return@onPinChanged
                _pairingPin.value = pin
            },
            onStateChanged = onStateChanged@{ state ->
                if (generations[Protocol.AIRPLAY] !== owner) return@onStateChanged
                _airPlayState.value = state
                when (state) {
                    ProtocolState.NEGOTIATING -> {
                        clearPhoto(Protocol.AIRPLAY)
                        clearActiveConnection(Protocol.AIRPLAY)
                        if (pendingHasVideo) bringToFront()
                    }
                    ProtocolState.CONNECTED   -> {
                        clearPhoto(Protocol.AIRPLAY)
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        notifications.updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                        // Only a video session needs the streaming Surface — an audio-only
                        // session must not pull the app to the front.
                        if (pendingHasVideo) bringToFront()
                    }
                    else -> {
                        clearActiveConnection(Protocol.AIRPLAY)
                        pendingHasVideo = false
                        updateNotification()
                    }
                }
            }
        )
        airPlayReceiver?.start()
        Logger.d("AirPlay receiver started")
    }

    /** Creates and starts the [MiracastReceiver]. */
    private fun startMiracast() {
        val owner = Any()
        generations[Protocol.MIRACAST] = owner
        miracastReceiver = MiracastReceiver(
            context = context,
            admitPlayback = { admit(Protocol.MIRACAST, owner) { miracastReceiver?.disconnectSender() } },
            releasePlayback = { ownership.release(owner) },
            surfaceProvider = surfaceProvider,
            onStateChanged = onStateChanged@{ state ->
                if (generations[Protocol.MIRACAST] !== owner) return@onStateChanged
                _miracastState.value = state
                if (state == ProtocolState.NEGOTIATING) bringToFront()
            }
        )
        miracastReceiver?.start()
        Logger.d("Miracast receiver started")
    }

    /** Creates and starts the [CastReceiver]. */
    private fun startCast() {
        val owner = Any()
        generations[Protocol.CAST] = owner
        castReceiver = CastReceiver(
            context = context,
            onStateChanged = { state -> if (generations[Protocol.CAST] === owner) _castState.value = state }
        )
        castReceiver?.start()
        Logger.d("Cast receiver started")
    }

    /** Creates and starts the [DlnaReceiver]. */
    private fun startDlna(settings: AppSettings) {
        val owner = Any()
        generations[Protocol.DLNA] = owner
        dlnaReceiver = DlnaReceiver(
            context = context,
            admitPlayback = { admit(Protocol.DLNA, owner) { dlnaReceiver?.sendRemoteCommand(DlnaReceiver.COMMAND_STOP) } },
            releasePlayback = { ownership.release(owner) },
            replaceSender = { preferences.replaceCurrentSender },
            displayName = settings.effectiveDisplayName,
            surfaceProvider = surfaceProvider,
            onPhotoReceived = onPhotoReceived@{ bytes, mimeType ->
                if (generations[Protocol.DLNA] !== owner) return@onPhotoReceived
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = mimeType, protocol = Protocol.DLNA
                )
                notifications.updateNotification(isRunning = true, streamingSenderName = "DLNA Controller")
            },
            onPhotoCleared = onPhotoCleared@{
                if (generations[Protocol.DLNA] !== owner) return@onPhotoCleared
                clearPhoto(Protocol.DLNA)
            },
            onStateChanged = onStateChanged@{ state ->
                if (generations[Protocol.DLNA] !== owner) return@onStateChanged
                _dlnaState.value = state
                when (state) {
                    ProtocolState.CONNECTED -> {
                        _activeConnection.value = ActiveConnection("DLNA Controller", Protocol.DLNA)
                        notifications.updateNotification(isRunning = true, streamingSenderName = "DLNA Controller")
                    }
                    else -> {
                        clearActiveConnection(Protocol.DLNA)
                        updateNotification()
                    }
                }
            }
        )
        dlnaReceiver?.start()
        Logger.d("DLNA receiver started")
    }

    /**
     * Stops every receiver and clears the session state.
     *
     * Every receiver is stopped inside its own try/catch: one failing protocol must not
     * keep the other three running, and it must not stop the remaining resources from
     * being released.
     */
    @Synchronized
    internal fun close() {
        closed = true
        stopAllReceiversInternal()
    }

    @Synchronized
    internal fun stopAllReceiversInternal() {
        Protocol.entries.forEach(::stop)
    }

    private fun admit(protocol: Protocol, owner: Any, disconnect: () -> Unit): Boolean {
        if (generations[protocol] !== owner) return false
        if (!ownership.acquire(owner, disconnect = disconnect)) return false
        if (generations[protocol] === owner) return true
        ownership.remove(owner)
        return false
    }

    @Synchronized
    internal fun stop(protocol: Protocol) {
        val owner = generations.remove(protocol)
        if (protocol == Protocol.AIRPLAY) audioApproval.cancel()
        try {
            when (protocol) {
                Protocol.AIRPLAY -> airPlayReceiver.also { airPlayReceiver = null }?.stop()
                Protocol.MIRACAST -> miracastReceiver.also { miracastReceiver = null }?.stop()
                Protocol.CAST -> castReceiver.also { castReceiver = null }?.stop()
                Protocol.DLNA -> dlnaReceiver.also { dlnaReceiver = null }?.stop()
            }
        } catch (e: Exception) {
            Logger.e("${protocol.name} stop error", e)
        } finally {
            if (owner != null) ownership.remove(owner)
            states.protocolState(protocol).value = ProtocolState.DISABLED
            clearActiveConnection(protocol)
            clearPhoto(protocol)
            if (protocol == Protocol.AIRPLAY) {
                _nowPlaying.value = null
                _pairingPin.value = null
            }
        }
    }

    private fun clearActiveConnection(protocol: Protocol) {
        _activeConnection.value?.takeIf { it.protocol == protocol }?.let {
            _activeConnection.compareAndSet(it, null)
        }
    }

    private fun clearPhoto(protocol: Protocol) {
        _photoFrame.value?.takeIf { it.protocol == protocol }?.let {
            _photoFrame.compareAndSet(it, null)
        }
    }

    internal fun updateNotification() {
        notifications.updateNotification(
            isRunning = hasReceivers,
            streamingSenderName = _activeConnection.value?.senderName
        )
    }

    // ─── Commands forwarded to a running receiver ────────────────────────────

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip
     * what the Mac/iPhone is streaming. No-op if no AirPlay sender has advertised a DACP
     * identity.
     */
    internal fun canControlAirPlaySender() = airPlayReceiver?.canControlSender() == true
    internal fun airPlayUrlPlayback() = airPlayReceiver?.urlPlayback()
    internal fun selectUrlTrack(session: Long, kind: app.ember.airreceiver.airplay.playback.PlaybackTrackKind, index: Int?) =
        airPlayReceiver?.selectUrlTrack(session, kind, index)

    internal fun setAirPlayUrlRate(rate: Float) = airPlayReceiver?.urlRate(rate)
    internal fun seekAirPlayUrl(seconds: Double) = airPlayReceiver?.urlSeek(seconds)
    internal fun stopAirPlayUrl() = airPlayReceiver?.stopUrlPlayback()

    internal fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    /** Sends a DAVP transport command to the DLNA renderer (play/pause/skip). */
    internal fun sendDlnaRemoteCommand(command: String) {
        dlnaReceiver?.sendRemoteCommand(command)
    }

    /**
     * Repeats the mDNS advertisement of the AirPlay receiver.
     *
     * Called by the [NetworkRecoveryMonitor] after the network came back — an NsdManager
     * registration does not survive a network outage.
     */
    internal fun readvertiseAirPlay() {
        airPlayReceiver?.readvertise()
    }

    /**
     * Repeats the advertisements of every receiver that has one.
     *
     * WHY both protocols: after a network outage the mDNS registration of the AirPlay
     * receiver is gone, and the DLNA renderer has to announce itself again — with a
     * new description URL when the device came back with a different IP address.
     * FR-61 asks every enabled receiver to re-advertise; a receiver that fails must
     * not keep the other one from advertising itself.
     */
    @Synchronized
    internal fun readvertiseAll() {
        if (closed) return
        readvertiseAirPlay()
        try {
            dlnaReceiver?.readvertise()
        } catch (e: Exception) {
            Logger.e("DLNA re-advertisement error", e)
        }
    }
}
