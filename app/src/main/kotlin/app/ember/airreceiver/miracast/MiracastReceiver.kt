// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.miracast

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.view.Surface
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import app.ember.airreceiver.service.ProtocolState
import app.ember.airreceiver.util.Logger
import app.ember.airreceiver.util.releaseQuietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * MiracastReceiver — Miracast (Wi-Fi Display / WFD) receiver service advertiser.
 *
 * WHY: Miracast allows Windows 10+ and Android devices to wirelessly mirror
 * their screen without being on the same Wi-Fi network. It uses Wi-Fi Direct
 * (P2P) to create a direct device-to-device connection.
 *
 * HOW: Implementation proceeds in phases:
 * - Phase 1: Architecture defined, P2P manager initialized
 * - Phase 2: Wi-Fi P2P service discovery advertised
 * - WFD RTSP session negotiation
 * - Phase 4 (M6): H.264 video decode + audio playback
 *
 * Miracast protocol stack:
 *   Wi-Fi Direct (P2P) → WFD RTSP → RTP/H.264 → MediaCodec → SurfaceView
 *
 * Key Android APIs used:
 *   - [WifiP2pManager]: for discovering peers and accepting connections
 *   - [WifiP2pManager.Channel]: communication channel to the P2P framework
 *   - Custom WFD RTSP: similar to AirPlay RTSP but with WFD-specific methods
 *
 * IMPORTANT LIMITATIONS (see ADR-001):
 * - Miracast requires Wi-Fi Direct, which some Android TV devices disable
 * - The WFD stack on Android TV is partly hidden (system APIs)
 * - Real-world compatibility must be tested on actual hardware
 * - Miracast is NOT available on Fire TV with standard APIs
 *
 * Example:
 *   val receiver = MiracastReceiver(context) { state -> updateUI(state) }
 *   receiver.start()  // begins P2P service advertisement
 *   receiver.stop()   // stops advertisement and closes session
 */
class MiracastReceiver(
    private val context: Context,
    private val surfaceProvider: () -> Surface? = { null },
    private val onStateChanged: (ProtocolState) -> Unit
) {

    // Android's Wi-Fi P2P manager — the entry point for all Wi-Fi Direct operations
    private var wifiP2pManager: WifiP2pManager? = null

    // The communication channel between the app and the Wi-Fi P2P framework
    private var channel: Channel? = null

    // The local DNS-SD service record advertised through Wi-Fi Direct.
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null

    // Whether the P2P service advertisement is currently active
    @Volatile
    private var isAdvertising = false

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var groupListener: BroadcastReceiver? = null
    private val rtspServer = WfdRtspServer(
        surface = surfaceProvider,
        onPreparing = { onStateChanged(ProtocolState.NEGOTIATING) },
        onSessionStarted = {
            Logger.i("Miracast WFD session connected")
            onStateChanged(ProtocolState.CONNECTED)
        },
        onSessionStopped = {
            Logger.i("Miracast WFD session stopped")
            if (isAdvertising) onStateChanged(ProtocolState.ADVERTISING)
        }
    )

    /**
     * Starts the Miracast receiver.
     *
     * Current implementation:
     * - Initializes the WifiP2pManager and Channel
     * - Logs availability of Wi-Fi Direct on this device
     * - Registers a local Wi-Fi Direct DNS-SD WFD service
     * - Opens the WFD RTSP control server on port 7236
     */
    fun start() {
        Logger.i("MiracastReceiver starting")
        initializeWifiP2p()
    }

    /**
     * Stops the Miracast receiver.
     *
     * Unregisters P2P service, disconnects any active WFD session,
     * and releases the WifiP2pManager channel.
     */
    fun stop() {
        Logger.i("MiracastReceiver stopping")
        groupListener?.let { runCatching { context.unregisterReceiver(it) } }
        groupListener = null
        if (wfdRegistered) publishDisplayRole(false)
        // Each step is released on its own so a failing P2P call cannot keep the WFD RTSP
        // server bound or the P2P channel open.
        try {
            releaseQuietly("Miracast P2P advertisement") { stopP2pAdvertisement() }
            releaseQuietly("Miracast RTSP server") { rtspServer.stop() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                releaseQuietly("Miracast P2P channel") { channel?.close() }
            }
        } finally {
            wifiP2pManager = null
            channel = null
            isAdvertising = false
            job.cancel()
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    /**
     * Initializes the WifiP2pManager and Channel.
     *
     * The [WifiP2pManager] is retrieved from Android's system services.
     * The [Channel] is the app's communication link to the P2P framework.
     *
     * If Wi-Fi Direct is not available on this device (some Android TV boxes
     * don't support it), [wifiP2pManager] will be null and we emit an ERROR state.
     */
    private fun initializeWifiP2p() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Logger.w("WifiP2pManager not available on this device — Miracast not supported")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        // Initialize the channel: connects the app to the Wi-Fi P2P framework
        // The looper parameter specifies which thread receives P2P framework callbacks
        channel = wifiP2pManager!!.initialize(
            context,
            context.mainLooper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // P2P framework disconnected — this can happen if Wi-Fi is turned off
                    Logger.w("WifiP2p channel disconnected")
                    onStateChanged(ProtocolState.ERROR)
                }
            }
        )

        Logger.i("WifiP2pManager initialized — registering P2P service")
        registerP2pService()
    }

    /**
     * Stops the P2P service advertisement.
     */
    private fun stopP2pAdvertisement() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val activeService = serviceInfo ?: return
        if (!isAdvertising) return

        try {
            manager.removeLocalService(
                activeChannel,
                activeService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.d("P2P service advertisement stopped")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.w("P2P service removal failed, reason=$reason (non-fatal)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Logger.e("Missing Wi-Fi P2P permission while removing Miracast service", e)
        }
        serviceInfo = null
        isAdvertising = false
    }

    private var wfdRegistered = false

    private fun publishDisplayRole(enable: Boolean) {
        if (Build.VERSION.SDK_INT < 34 || context.checkSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY) != PackageManager.PERMISSION_GRANTED) return
        val activeChannel = channel ?: return
        val info = android.net.wifi.p2p.WifiP2pWfdInfo().apply {
            isEnabled = enable
            setDeviceType(android.net.wifi.p2p.WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK)
            isSessionAvailable = enable
            controlPort = WFD_RTSP_PORT
            maxThroughput = 50
        }
        wifiP2pManager?.setWfdInfo(activeChannel, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { wfdRegistered = enable }
            override fun onFailure(reason: Int) { Logger.w("Wireless display role unavailable ($reason)") }
        })
    }

    private fun monitorGroup(manager: WifiP2pManager, channel: Channel) {
        fun refresh() {
            if (!job.isActive) return
            manager.requestConnectionInfo(channel) { info ->
                if (info == null || !info.groupFormed) {
                    rtspServer.disconnect()
                    return@requestConnectionInfo
                }
                manager.requestGroupInfo(channel) { group ->
                    if (group == null || !job.isActive) return@requestGroupInfo
                    rtspServer.setGroupInterface(if (info.isGroupOwner) info.groupOwnerAddress else null)
                    val source = if (info.isGroupOwner) group.clientList.firstOrNull() else group.owner
                    val address = if (!info.isGroupOwner) info.groupOwnerAddress
                        else if (Build.VERSION.SDK_INT >= 35) source?.ipAddress else null
                    val port = if (Build.VERSION.SDK_INT >= 30) source?.wfdInfo?.controlPort else null
                    if (address != null) rtspServer.connectToSource(scope, address,
                        port?.takeIf { it in 1..65535 } ?: WFD_RTSP_PORT)
                }
            }
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(listener, filter)
        groupListener = listener
        refresh()
    }

    /**
     * Registers the WFD local service record used by Wi-Fi Direct discovery.
     *
     * Android exposes Wi-Fi Direct service discovery through DNS-SD TXT records.
     * Miracast senders look for `_wfd._tcp` and then continue with WFD capability
     * negotiation over RTSP after the P2P group is formed.
     */
    private fun registerP2pService() {
        val manager = wifiP2pManager
        val activeChannel = channel
        if (manager == null || activeChannel == null) {
            Logger.w("Cannot register Miracast P2P service before Wi-Fi P2P initialization")
            onStateChanged(ProtocolState.ERROR)
            return
        }
        if (!hasWifiP2pPermission()) {
            Logger.w("Cannot register Miracast P2P service: missing Wi-Fi Direct permission")
            onStateChanged(ProtocolState.PERMISSION_REQUIRED)
            return
        }

        val txtRecord = mapOf(
            "wfd_device_type" to "primary_sink",
            "wfd_session_available" to "1",
            "wfd_rtsp_port" to WFD_RTSP_PORT.toString(),
            "wfd_video_formats" to "h264-chp,h264-cbp",
            "wfd_audio_codecs" to "aac"
        )
        val localService = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE_NAME,
            SERVICE_TYPE_WFD,
            txtRecord
        )

        try {
            manager.addLocalService(
                activeChannel,
                localService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!job.isActive) return
                        serviceInfo = localService
                        isAdvertising = true
                        rtspServer.start(scope)
                        runCatching { publishDisplayRole(true) }
                            .onFailure { Logger.w("Platform display role unavailable") }
                        runCatching { monitorGroup(manager, activeChannel) }
                            .onFailure { Logger.w("Mirror group updates unavailable") }
                        Logger.i("Miracast WFD P2P service advertised")
                        onStateChanged(ProtocolState.ADVERTISING)
                    }

                    override fun onFailure(reason: Int) {
                        serviceInfo = null
                        isAdvertising = false
                        Logger.e("Miracast WFD P2P service registration failed, reason=$reason")
                        onStateChanged(ProtocolState.ERROR)
                    }
                }
            )
        } catch (e: SecurityException) {
            serviceInfo = null
            isAdvertising = false
            Logger.e("Missing Wi-Fi P2P permission while registering Miracast service", e)
            onStateChanged(ProtocolState.ERROR)
        }
    }

    private fun hasWifiP2pPermission(): Boolean {
        return context.checkSelfPermission(PERMISSION_NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(PERMISSION_ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val WFD_RTSP_PORT = 7236
        private const val SERVICE_INSTANCE_NAME = "Ember AirReceiver"
        private const val SERVICE_TYPE_WFD = "_wfd._tcp"
        private const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        private const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    }
}
