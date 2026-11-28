// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver

import app.ember.airreceiver.util.Logger
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.ember.airreceiver.airplay.nowplaying.NowPlayingInfo
import app.ember.airreceiver.service.PhotoFrame
import app.ember.airreceiver.service.ProtocolState
import app.ember.airreceiver.service.ServiceController
import app.ember.airreceiver.ui.FragmentNavigator
import app.ember.airreceiver.ui.NowPlayingScreen
import app.ember.airreceiver.ui.PhotoScreen
import app.ember.airreceiver.ui.PinScreen
import app.ember.airreceiver.ui.ServiceConnectionBinder
import app.ember.airreceiver.ui.StreamingScreen

/**
 * AirReceiverActivity — The single Activity hosting Ember AirReceiver's navigation and fragments.
 *
 * WHY: Ember AirReceiver uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. AirReceiverService is started on app launch.
 *
 * The two helpers keep the Activity itself small: [FragmentNavigator] owns the nav
 * panel and the fragment transactions, [ServiceConnectionBinder] owns the binding to
 * AirReceiverService and mirrors the service state into the overlay decision.
 */
class AirReceiverActivity : AppCompatActivity() {

    private val localNetworkPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ServiceController.start(this)
            else showLocalNetworkPermissionHelp()
        }

    private var returningFromNetworkSettings = false

    internal fun requestLocalNetworkPermissionIfNeeded(): Boolean {
        val access = app.ember.airreceiver.service.LocalNetworkAccess
        if (access.hasAccess(this)) return false
        localNetworkPermissionRequest.launch(access.PERMISSION)
        return true
    }

    private fun showLocalNetworkPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.protocol_state_permission_required)
            .setMessage(R.string.ember_local_network_permission)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.miracast_open_app_settings) { _, _ ->
                returningFromNetworkSettings = true
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (returningFromNetworkSettings) {
            returningFromNetworkSettings = false
            ServiceController.start(this)
        }
    }

    private val miracastPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            ServiceController.restart(this)
            if (grants[miracastPermission] != true) {
                showMiracastPermissionHelp()
            }
        }

    private val miracastPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    internal fun requestMiracastPermissionIfNeeded(): Boolean {
        if (requestLocalNetworkPermissionIfNeeded()) return true
        if (getSystemService(WIFI_P2P_SERVICE) == null ||
            ContextCompat.checkSelfPermission(this, miracastPermission) == PackageManager.PERMISSION_GRANTED
        ) return false

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(miracastPermission)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        miracastPermissionRequest.launch(permissions)
        return true
    }

    private fun showMiracastPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.protocol_state_permission_required)
            .setMessage(R.string.miracast_permission_help)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.miracast_open_app_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
            .show()
    }

    // UI references
    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout

    // The SurfaceView for full-screen video output
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private lateinit var pinScreen: PinScreen

    // Navigation — owns the nav panel highlight and the content fragment swap.
    private lateinit var navigator: FragmentNavigator

    // Service binding — gives access to state flows for showing/hiding the streaming overlay
    private lateinit var binder: ServiceConnectionBinder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.d("AirReceiverActivity created")
        bindViews()
        setupOverlayScreens()
        setupNavigation()
        setupServiceBinding()

        // Show HomeFragment on first launch
        if (savedInstanceState == null) {
            navigator.showHome()
        }

        // Start the service immediately so it's running before any sender discovers us
        ServiceController.start(this)

        // Android 13+ requires an explicit runtime grant for POST_NOTIFICATIONS
        if (!requestLocalNetworkPermissionIfNeeded()) requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        // Bind so we can observe StateFlows and supply the video Surface
        binder.bind()
    }

    override fun onStop() {
        super.onStop()
        // Clear surface reference before unbinding to avoid holding a dead Surface
        binder.unbind()
    }

    override fun onDestroy() {
        super.onDestroy()
        // A user-initiated exit (Back out of the app) should end any active mirror — closing the
        // service stops the receiver, which drops the RTSP connection so the sender stops mirroring
        // too. isFinishing distinguishes a real exit from a config-change recreation (where the
        // service must keep running). Backgrounding via Home goes through onStop only (no destroy),
        // so the receiver keeps advertising for a quick return.
        if (isFinishing) {
            Logger.d("AirReceiverActivity finishing — stopping service so mirroring doesn't linger")
            ServiceController.stop(this)
        } else {
            Logger.d("AirReceiverActivity destroyed (recreation) — leaving service running")
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews() {
        navItemHome       = findViewById(R.id.nav_item_home)
        navItemSettings   = findViewById(R.id.nav_item_settings)
        contentContainer  = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the StreamingScreen (SurfaceView for video) and adds it to the
     * streaming_container. Created eagerly so the Surface is ready before streaming starts.
     */
    private fun setupOverlayScreens() {
        streamingScreen = StreamingScreen(this)
        lifecycleScope.launch {
            app.ember.airreceiver.settings.SettingsRepository(this@AirReceiverActivity).settingsFlow.collect { settings ->
                streamingScreen.setSafeArea(settings.safeAreaPercent)
            }
        }
        photoScreen = PhotoScreen(this)
        nowPlayingScreen = NowPlayingScreen(this)
        pinScreen = PinScreen(this)
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        streamingContainer.addView(pinScreen)
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    /**
     * Creates the [FragmentNavigator] and sets up click listeners for the navigation
     * panel items. The navigator also updates the visual selected state (text color)
     * of the active item.
     */
    private fun setupNavigation() {
        navigator = FragmentNavigator(supportFragmentManager, navItemHome, navItemSettings)
        navigator.setupNavigation()
    }

    /**
     * Creates the [ServiceConnectionBinder] that binds the Activity to AirReceiverService.
     *
     * The binder mirrors the service state and reports every change through
     * [updateOverlay], so the overlay always reflects the current session.
     */
    private fun setupServiceBinding() {
        binder = ServiceConnectionBinder(
            context = this,
            scope = lifecycleScope,
            surfaceProvider = { getVideoSurface() },
            onStateChanged = { updateOverlay() }
        )
    }

    internal fun restartRunningReceivers() {
        binder.restartRunningReceivers()
    }

    /**
     * Shows the full-screen streaming overlay (called by AirReceiverService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        setKeepScreenOn(true)
    }

    fun showPhotoScreen(photoFrame: PhotoFrame) {
        if (photoScreen.showPhoto(photoFrame.bytes)) {
            streamingScreen.visibility = View.GONE
            nowPlayingScreen.visibility = View.GONE
            pinScreen.visibility = View.GONE
            photoScreen.visibility = View.VISIBLE
            streamingContainer.visibility = View.VISIBLE
            streamingContainer.bringToFront()
            setKeepScreenOn(true)
        }
    }

    /** Shows the audio-only now-playing card (AirPlay audio with no video). */
    fun showNowPlayingScreen(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        // Audio-only too: the sender is streaming, so the TV must not doze off mid-track.
        setKeepScreenOn(true)
    }

    /**
     * Hides the streaming overlay and returns to the normal app UI.
     * Called when a stream ends.
     */
    fun hideStreamingScreen() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.GONE
        setKeepScreenOn(false)
    }

    /**
     * Holds the display awake for as long as something is actually being received.
     *
     * Without this the TV's own screensaver / power-saving timer fires straight through an
     * active session — mirroring simply stops being watchable after the timeout, which is
     * what every other casting app avoids. `FLAG_KEEP_SCREEN_ON` is the right instrument
     * rather than a `PowerManager` wake lock: it needs no permission, it is scoped to this
     * window, and Android drops it automatically if the activity goes away, so a crash or a
     * forgotten `false` can never leave the panel burning indefinitely.
     *
     * Set from every path that raises or lowers the overlay: mirroring, photos, and
     * audio-only now-playing all count as "in use".
     */
    private fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        Logger.d("Keep-screen-on: $keepOn")
    }

    /** Returns the SurfaceView Surface for the VideoDecoder. */
    fun getVideoSurface() = streamingScreen.getSurface()

    /**
     * Routes TV-remote media keys to the AirPlay sender (DACP reverse control) while audio-only or a
     * stream is showing — so the remote can play/pause/skip what the Mac/iPhone is streaming. Returns
     * false for other keys so normal navigation is unaffected.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::streamingScreen.isInitialized && streamingScreen.visibility == View.VISIBLE &&
            binder.urlPlayback != null && streamingScreen.playbackControls.handleRemote(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val overlayActive = binder.nowPlaying != null ||
            binder.airPlayState == ProtocolState.CONNECTED ||
            binder.dlnaState == ProtocolState.CONNECTED
        if (overlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> app.ember.airreceiver.airplay.nowplaying.DacpClient.CMD_PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> app.ember.airreceiver.airplay.nowplaying.DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> app.ember.airreceiver.airplay.nowplaying.DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> app.ember.airreceiver.airplay.nowplaying.DacpClient.CMD_FF
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> app.ember.airreceiver.airplay.nowplaying.DacpClient.CMD_REW
                else -> null
            }
            if (command != null) {
                if (binder.dlnaState == ProtocolState.CONNECTED) {
                    val dlnaCommand = when (keyCode) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> app.ember.airreceiver.dlna.DlnaReceiver.COMMAND_PAUSE
                        android.view.KeyEvent.KEYCODE_MEDIA_STOP -> app.ember.airreceiver.dlna.DlnaReceiver.COMMAND_STOP
                        else -> app.ember.airreceiver.dlna.DlnaReceiver.COMMAND_PLAY_PAUSE
                    }
                    binder.sendDlnaRemoteCommand(dlnaCommand)
                } else {
                    binder.sendAirPlayRemoteCommand(command)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * On older versions the permission is granted automatically with the manifest declaration.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001
    }

    // ─── Streaming overlay ────────────────────────────────────────────────────

    /**
     * Decides which overlay the current session state calls for and shows it.
     *
     * Called by the [ServiceConnectionBinder] after every mirrored state change.
     */
    private fun updateOverlay() {
        val controls = streamingScreen.playbackControls
        controls.setPlayback(binder.urlPlayback)
        streamingScreen.setCaption(binder.urlPlayback?.caption)
        controls.onTrack = { session, kind, index -> binder.selectUrlTrack(session, kind, index) }
        controls.onRate = { binder.setUrlRate(it) }
        controls.onSeek = { binder.seekUrl(it) }
        controls.onStop = { binder.stopUrl() }
        val photoFrame = binder.photoFrame
        val nowPlaying = binder.nowPlaying
        val pin = binder.pairingPin
        when {
            // PIN pairing (access control) happens before streaming — show the code over everything.
            pin != null -> showPinScreen(pin)
            // Audio-only AirPlay (system audio, Music, podcasts): show the now-playing card instead
            // of the black video surface. Set whenever audio plays without video.
            nowPlaying != null -> showNowPlayingScreen(nowPlaying)
            photoFrame != null -> showPhotoScreen(photoFrame)
            binder.miracastState == ProtocolState.NEGOTIATING ||
                binder.miracastState == ProtocolState.CONNECTED -> showStreamingScreen()
            binder.airPlayState == ProtocolState.NEGOTIATING ||
                binder.airPlayState == ProtocolState.CONNECTED -> showStreamingScreen()
            binder.dlnaState == ProtocolState.CONNECTED -> showStreamingScreen()
            else -> hideStreamingScreen()
        }
    }

    /** Shows the AirPlay pairing PIN over the full screen during SRP pair-setup. */
    fun showPinScreen(pin: String) {
        pinScreen.setPin(pin)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }
}
