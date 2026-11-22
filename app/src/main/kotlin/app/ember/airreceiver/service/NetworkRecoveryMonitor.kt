package app.ember.airreceiver.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import app.ember.airreceiver.dlna.DlnaNetworkAddress
import app.ember.airreceiver.util.Logger

/** Coalesces LAN handoffs and address renewals before rebuilding discovery registrations. */
internal class NetworkRecoveryMonitor(
    private val context: Context,
    private val isServiceRunning: () -> Boolean,
    private val onNetworkRestored: () -> Unit
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var worker: HandlerThread? = null

    fun registerNetworkCallback() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val thread = HandlerThread("Ember discovery").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        val known = mutableMapOf<Network, Set<String>>()
        val usbNetworks = mutableSetOf<Network>()
        var previouslyConnectedUsb = emptySet<Network>()
        var advertised = DlnaNetworkAddress.localIpv4Address()
        var previousNetwork: Network? = null
        var lostRegistration = false
        val refresh = Runnable {
            val address = DlnaNetworkAddress.localIpv4Address()
            val network = known.entries.firstOrNull { address in it.value }?.key
            val connectedUsb = usbNetworks.filter { known[it]?.isNotEmpty() == true }.toSet()
            val usbChanged = connectedUsb != previouslyConnectedUsb
            if (usbChanged) Logger.i("USB network link count: ${connectedUsb.size}")
            previouslyConnectedUsb = connectedUsb
            if (network == null && !usbChanged) return@Runnable
            val changed = usbChanged || address != advertised || lostRegistration ||
                (previousNetwork != null && previousNetwork != network)
            previousNetwork = network
            advertised = address
            lostRegistration = false
            if (changed && isServiceRunning()) onNetworkRestored()
        }
        fun schedule() {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 500)
        }
        val observer = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress }.toSet()
                handler.post {
                    known[network] = addresses
                    schedule()
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (Build.VERSION.SDK_INT < 31) return
                val usb = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                handler.post {
                    val changed = if (usb) usbNetworks.add(network) else usbNetworks.remove(network)
                    if (changed) schedule()
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (network == previousNetwork || known[network]?.contains(advertised) == true) {
                        lostRegistration = true
                    }
                    known.remove(network)
                    usbNetworks.remove(network)
                    schedule()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) addTransportType(NetworkCapabilities.TRANSPORT_USB)
            }
            .build()
        try {
            manager.registerNetworkCallback(request, observer)
            callback = observer
        } catch (e: RuntimeException) {
            thread.quit()
            worker = null
            Logger.e("Could not register LAN discovery monitor", e)
        }
    }

    fun unregisterNetworkCallback() {
        callback?.let { observer ->
            runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(observer) }
        }
        callback = null
        worker?.quit()
        worker = null
    }
}
