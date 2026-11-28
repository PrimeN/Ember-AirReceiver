// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.ember.airreceiver.BuildConfig
import app.ember.airreceiver.AirReceiverActivity
import app.ember.airreceiver.R
import app.ember.airreceiver.service.AirReceiverService
import app.ember.airreceiver.service.Protocol
import app.ember.airreceiver.service.ProtocolState
import app.ember.airreceiver.service.ServiceController
import app.ember.airreceiver.service.ServiceState
import app.ember.airreceiver.settings.SettingsRepository
import app.ember.airreceiver.util.Logger
import app.ember.airreceiver.util.NetworkUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * HomeFragment — The main screen of Ember AirReceiver.
 *
 * WHY: Shows the status of all three receiver protocols (AirPlay / Miracast / Cast)
 * and provides Start / Stop / Restart controls. Designed for TV: large cards,
 * D-pad navigable, Google TV Streamer design language.
 *
 * HOW: Binds to [AirReceiverService] to receive real-time state updates.
 * User interactions call [ServiceController] to send commands to the service.
 *
 * Navigation: accessed via the "Home" item in AirReceiverActivity's nav panel.
 */
class HomeFragment : Fragment() {

    // Service binding — gives direct access to AirReceiverService StateFlows
    private var service: AirReceiverService? = null
    private var isBound = false
    private var stateObserver: kotlinx.coroutines.Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? AirReceiverService.LocalBinder)?.getService()
            Logger.d("HomeFragment: bound to AirReceiverService")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stateObserver?.cancel()
            Logger.d("HomeFragment: unbound from AirReceiverService")
        }
    }

    // View references — bound in onViewCreated
    private lateinit var textDeviceName: TextView
    private lateinit var textServiceState: TextView
    private lateinit var dotServiceState: View
    private lateinit var cardAirPlay: View
    private lateinit var cardMiracast: View
    private lateinit var cardCast: View
    private lateinit var cardDlna: View
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRestart: Button
    private var receiverName = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureProtocolCards()
        configureButtons()
        showDeviceName()
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service so we can observe its StateFlows
        val intent = Intent(requireContext(), AirReceiverService::class.java)
        isBound = requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stateObserver?.cancel()
        service = null
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        textDeviceName   = view.findViewById(R.id.text_device_name)
        textServiceState = view.findViewById(R.id.text_service_state)
        dotServiceState  = view.findViewById(R.id.dot_service_state)
        cardAirPlay      = view.findViewById(R.id.card_airplay)
        cardMiracast     = view.findViewById(R.id.card_miracast)
        cardCast         = view.findViewById(R.id.card_cast)
        cardDlna         = view.findViewById(R.id.card_dlna)
        btnStart         = view.findViewById(R.id.btn_start)
        btnStop          = view.findViewById(R.id.btn_stop)
        btnRestart       = view.findViewById(R.id.btn_restart)
    }

    /**
     * Sets the static content on each protocol card: icon and protocol name.
     * The dynamic parts (state, detail text) are updated when service state changes.
     */
    private fun configureProtocolCards() {
        setupCard(cardAirPlay,   R.drawable.ic_airplay,  R.string.protocol_airplay, Protocol.AIRPLAY)
        setupCard(cardMiracast,  R.drawable.ic_miracast, R.string.protocol_miracast, Protocol.MIRACAST)
        setupCard(cardCast,      R.drawable.ic_cast,     R.string.protocol_cast, Protocol.CAST)
        setupCard(cardDlna,      R.drawable.ic_dlna,     R.string.protocol_dlna, Protocol.DLNA)
    }

    private fun setupCard(card: View, iconRes: Int, nameRes: Int, protocol: Protocol) {
        card.isFocusable = protocol != Protocol.CAST || BuildConfig.FLAVOR == "googletv"
        if (card.isFocusable) {
            card.isFocusable = true
            card.setOnClickListener {
                val state = service?.receiverState(protocol)?.value ?: return@setOnClickListener
                val enabled = !state.isServing && state != ProtocolState.NOT_CONFIGURED
                (activity as? AirReceiverActivity)?.setReceiverEnabled(protocol, enabled)
            }
        }
        card.findViewById<android.widget.ImageView>(R.id.img_protocol_icon)?.setImageResource(iconRes)
        card.findViewById<TextView>(R.id.text_protocol_name)?.setText(nameRes)
    }

    /**
     * Configures Start / Stop / Restart button click listeners.
     * Calls [ServiceController] which sends Intent actions to [AirReceiverService].
     */
    private fun configureButtons() {
        requireView().findViewById<Button>(R.id.btn_connection_help).setOnClickListener { showConnectionHelp() }
        btnStart.setOnClickListener {
            Logger.d("User tapped Start")
            val context = requireContext()
            val start = { ServiceController.start(context) }
            if ((activity as? AirReceiverActivity)?.requestLocalNetworkPermissionIfNeeded(start) != true) start()
        }
        btnStop.setOnClickListener {
            Logger.d("User tapped Stop")
            ServiceController.stop(requireContext())
        }
        btnRestart.setOnClickListener {
            Logger.d("User tapped Restart")
            val context = requireContext()
            val restart = { ServiceController.restart(context) }
            val host = activity as? AirReceiverActivity
            val afterNetwork = {
                if (service?.miracastState?.value != ProtocolState.PERMISSION_REQUIRED ||
                    host?.requestMiracastPermissionIfNeeded(restart) != true) restart()
            }
            if (host?.requestLocalNetworkPermissionIfNeeded(afterNetwork) != true) afterNetwork()
        }
    }

    /**
     * Shows the device's AirPlay name on the HomeScreen so the user knows
     * what to look for in their sender's picker.
     */
    private fun showDeviceName() {
        val context = requireContext()
        val systemName = NetworkUtils.getDeviceName(context)
        receiverName = systemName
        textDeviceName.text = getString(R.string.home_device_visible_as, receiverName)
        viewLifecycleOwner.lifecycleScope.launch {
            SettingsRepository(context).settingsFlow.collectLatest { settings ->
                receiverName = settings.effectiveDisplayName.ifEmpty { systemName }
                textDeviceName.text = getString(R.string.home_device_visible_as, receiverName)
            }
        }
    }

    private fun showConnectionHelp() {
        val topics = listOf(
            Triple(R.string.protocol_airplay, R.string.help_airplay, cardAirPlay),
            Triple(R.string.protocol_miracast, R.string.help_miracast, cardMiracast),
            Triple(R.string.protocol_cast, R.string.help_cast, cardCast),
            Triple(R.string.protocol_dlna, R.string.help_dlna, cardDlna)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_connection_help)
            .setItems(topics.map { getString(it.first) }.toTypedArray()) { _, selected ->
                val (title, instructions, card) = topics[selected]
                val status = card.findViewById<TextView>(R.id.text_protocol_detail).text
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(getString(instructions, receiverName, status))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── State Observation ───────────────────────────────────────────────────

    /**
     * Starts collecting state updates from [AirReceiverService].
     * Called after the service is bound. Each StateFlow is collected independently
     * so that a change in one protocol card doesn't trigger a full UI redraw.
     */
    private fun observeServiceState() {
        val svc = service ?: return
        stateObserver?.cancel()
        stateObserver = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                svc.serviceState.collectLatest { state -> updateServiceStateBadge(state) }
            }
            launch {
                svc.airPlayState.collectLatest { state -> updateProtocolCard(cardAirPlay, state) }
            }
            launch {
                svc.miracastState.collectLatest { state -> updateProtocolCard(cardMiracast, state) }
            }
            launch {
                svc.castState.collectLatest { state -> updateProtocolCard(cardCast, state) }
            }
            launch {
                svc.dlnaState.collectLatest { state -> updateProtocolCard(cardDlna, state) }
            }
        }
    }

    /**
     * Updates the global service state badge (top-right corner of HomeScreen).
     * Colors and text reflect whether the service is running, stopped, or restarting.
     */
    private fun updateServiceStateBadge(state: ServiceState) {
        val (textRes, colorRes) = when (state) {
            is ServiceState.Running    -> Pair(R.string.service_state_running,    R.color.status_running)
            is ServiceState.Stopped    -> Pair(R.string.service_state_stopped,    R.color.status_stopped)
            is ServiceState.Restarting -> Pair(R.string.service_state_restarting, R.color.status_transitioning)
            is ServiceState.Error      -> Pair(R.string.service_state_error,      R.color.status_stopped)
        }
        textServiceState.setText(textRes)
        dotServiceState.background.setTint(requireContext().getColor(colorRes))
    }

    /**
     * Updates a single protocol status card with the current [ProtocolState].
     *
     * @param card      The card root view (cardAirPlay, cardMiracast, or cardCast).
     * @param state     The current state of this protocol.
     */
    private fun updateProtocolCard(card: View, state: ProtocolState) {
        val dot    = card.findViewById<View>(R.id.dot_protocol_status)
        val stateText = card.findViewById<TextView>(R.id.text_protocol_state)
        val detail = card.findViewById<TextView>(R.id.text_protocol_detail)

        val (stateRes, colorRes, detailRes) = when (state) {
            ProtocolState.DISABLED -> Triple(
                R.string.protocol_state_disabled, R.color.status_disabled,
                if (card == cardCast && BuildConfig.FLAVOR == "firetv") {
                    R.string.protocol_detail_cast_firetv
                } else {
                    R.string.protocol_detail_disabled
                }
            )
            ProtocolState.STARTING -> Triple(R.string.protocol_state_starting, R.color.status_transitioning, R.string.protocol_detail_restarting)
            ProtocolState.ADVERTISING -> Triple(R.string.protocol_state_advertising, R.color.status_running,   R.string.protocol_detail_waiting)
            ProtocolState.CONNECTED   -> Triple(R.string.protocol_state_connected,   R.color.status_running,   R.string.protocol_detail_connected)
            ProtocolState.NEGOTIATING -> Triple(R.string.protocol_state_preparing,
                R.color.status_transitioning, R.string.protocol_detail_preparing)
            ProtocolState.PERMISSION_REQUIRED -> Triple(
                R.string.protocol_state_permission_required, R.color.status_stopped,
                if (app.ember.airreceiver.service.LocalNetworkAccess.hasAccess(requireContext())) {
                    R.string.protocol_detail_miracast_permission
                } else R.string.ember_local_network_permission
            )
            ProtocolState.NOT_CONFIGURED -> Triple(
                R.string.protocol_state_not_configured, R.color.status_disabled,
                R.string.protocol_detail_cast_not_configured
            )
            ProtocolState.ERROR       -> Triple(R.string.protocol_state_error,       R.color.status_stopped,   R.string.protocol_detail_error)
        }

        if (card.isClickable) {
            val name = card.findViewById<TextView>(R.id.text_protocol_name)?.text
            val turnOff = state.isServing || state == ProtocolState.NOT_CONFIGURED
            card.contentDescription = getString(
                if (turnOff) R.string.protocol_tile_turn_off else R.string.protocol_tile_turn_on,
                name, getString(stateRes)
            )
        }
        stateText.setText(stateRes)
        detail.setText(detailRes)
        dot.background.setTint(requireContext().getColor(colorRes))
    }

}
