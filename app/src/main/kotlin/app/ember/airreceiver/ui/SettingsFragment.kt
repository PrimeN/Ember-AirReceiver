// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.ember.airreceiver.AirReceiverActivity
import app.ember.airreceiver.BuildConfig
import app.ember.airreceiver.R
import app.ember.airreceiver.airplay.StreamStats
import app.ember.airreceiver.service.Protocol
import app.ember.airreceiver.service.ServiceController
import app.ember.airreceiver.settings.AppSettings
import app.ember.airreceiver.settings.SettingsRepository
import app.ember.airreceiver.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SettingsFragment — Settings screen for Ember AirReceiver.
 *
 * WHY: Centralizes all user-configurable options in one screen. By separating
 * settings into their own Fragment, we keep AirReceiverActivity lean and make it easy
 * to navigate to/from settings via the nav panel.
 *
 * HOW: Reads current settings from [SettingsRepository] and populates the UI.
 * Each toggle/row saves immediately when changed (no "Save" button needed).
 * Settings changes take effect on the next service restart.
 *
 * Navigation: accessed via the "Settings" item in AirReceiverActivity's nav panel.
 */
class SettingsFragment : Fragment() {

    private lateinit var settingsRepository: SettingsRepository

    // Section header TextViews — set via include layout tag IDs
    private lateinit var headerDisplay: TextView
    private lateinit var headerProtocols: TextView
    private lateinit var headerAirPlay: TextView
    private lateinit var headerService: TextView
    private lateinit var headerDeveloper: TextView
    private lateinit var headerAbout: TextView

    // Settings rows
    private lateinit var rowDisplayName: LinearLayout
    private lateinit var textDisplayNameValue: TextView
    private lateinit var rowAirPlay: View
    private lateinit var rowMiracast: View
    private lateinit var rowCast: View
    private lateinit var rowDlna: View
    private lateinit var rowLowBandwidth: View
    private lateinit var rowAudioOnly: View
    private lateinit var rowSenderHandoff: View
    private lateinit var rowMirrorAudio: View
    private lateinit var receiverOptions: ReceiverOptions
    private lateinit var rowIdlePreview: View
    private lateinit var rowAutoFullscreen: View
    private lateinit var rowAutoAudioMode: View
    private lateinit var rowStartOnLaunch: View
    private lateinit var rowStartOnBoot: View
    private lateinit var rowDebugOverlay: View
    private lateinit var textVersionValue: TextView
    private lateinit var rowDiagnostics: View
    private lateinit var rowPrivacy: View
    private lateinit var rowReset: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsRepository = SettingsRepository(requireContext())
        bindViews(view)
        setSectionTitles()
        setRowLabels()
        loadAndPopulate()
    }

    // ─── View Binding ────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        // Each header is an <include> of settings_section_header.xml (a bare
        // TextView). The include's android:id IS the TextView's id, so look it up
        // directly — no nested lookup.
        headerDisplay   = view.findViewById(R.id.header_display)
        headerProtocols = view.findViewById(R.id.header_protocols)
        headerAirPlay   = view.findViewById(R.id.header_airplay)
        headerService   = view.findViewById(R.id.header_service)
        headerDeveloper = view.findViewById(R.id.header_developer)
        headerAbout     = view.findViewById(R.id.header_about)

        rowDisplayName      = view.findViewById(R.id.row_display_name)
        textDisplayNameValue = view.findViewById(R.id.text_display_name_value)
        rowAirPlay          = view.findViewById(R.id.row_airplay)
        rowMiracast         = view.findViewById(R.id.row_miracast)
        rowCast             = view.findViewById(R.id.row_cast)
        rowDlna             = view.findViewById(R.id.row_dlna)
        rowLowBandwidth = view.findViewById(R.id.row_low_bandwidth)
        rowAudioOnly = view.findViewById(R.id.row_airplay_audio_only)
        rowSenderHandoff = view.findViewById(R.id.row_sender_handoff)
        rowMirrorAudio      = view.findViewById(R.id.row_mirror_audio)
        receiverOptions = ReceiverOptions(view, ::save)
        rowIdlePreview = view.findViewById(R.id.row_idle_preview)
        rowAutoFullscreen = view.findViewById(R.id.row_auto_fullscreen)
        rowAutoAudioMode = view.findViewById(R.id.row_auto_audio_mode)
        rowStartOnLaunch    = view.findViewById(R.id.row_start_on_launch)
        rowStartOnBoot      = view.findViewById(R.id.row_start_on_boot)
        rowDebugOverlay     = view.findViewById(R.id.row_debug_overlay)
        textVersionValue    = view.findViewById(R.id.text_version_value)
        rowDiagnostics      = view.findViewById(R.id.row_diagnostics)
        rowPrivacy          = view.findViewById(R.id.row_privacy)
        rowReset            = view.findViewById(R.id.row_reset)
    }

    /** Sets all section header titles from string resources. */
    private fun setSectionTitles() {
        headerDisplay.setText(R.string.settings_section_display)
        headerProtocols.setText(R.string.settings_section_protocols)
        headerAirPlay.setText(R.string.settings_section_airplay)
        headerService.setText(R.string.settings_section_service)
        headerDeveloper.setText(R.string.settings_section_developer)
        headerAbout.setText(R.string.settings_section_about)
    }

    /** Sets all row labels and subtitles from string resources. */
    private fun setRowLabels() {
        configureToggleRow(rowAirPlay,      R.string.setting_airplay_enabled,    R.string.setting_airplay_subtitle)
        configureToggleRow(rowMiracast,     R.string.setting_miracast_enabled,   R.string.setting_miracast_subtitle)
        configureToggleRow(rowCast,         R.string.setting_cast_enabled,       R.string.setting_cast_subtitle)
        configureToggleRow(rowDlna,         R.string.setting_dlna_enabled,       R.string.setting_dlna_subtitle)
        configureToggleRow(rowLowBandwidth, R.string.setting_low_bandwidth, R.string.setting_low_bandwidth_detail)
        configureToggleRow(rowAudioOnly, R.string.setting_airplay_audio_only, R.string.setting_airplay_audio_only_detail)
        configureToggleRow(rowSenderHandoff, R.string.setting_sender_handoff, R.string.setting_sender_handoff_detail)
        configureToggleRow(rowMirrorAudio,  R.string.setting_mirror_audio,       R.string.setting_mirror_audio_subtitle)
        configureToggleRow(rowIdlePreview, R.string.setting_idle_preview, R.string.setting_idle_preview_detail)
        configureToggleRow(rowAutoFullscreen, R.string.setting_auto_fullscreen, R.string.setting_auto_fullscreen_detail)
        configureToggleRow(rowAutoAudioMode, R.string.setting_auto_audio_mode, R.string.setting_auto_audio_mode_detail)
        configureToggleRow(rowStartOnLaunch, R.string.setting_start_on_launch, R.string.setting_start_on_launch_detail)
        configureToggleRow(rowStartOnBoot,  R.string.setting_start_on_boot,      0)
        configureToggleRow(rowDebugOverlay, R.string.setting_debug_overlay,      R.string.setting_debug_overlay_subtitle)

        textVersionValue.text = BuildConfig.VERSION_NAME
    }

    /**
     * Sets the label and optional subtitle on a toggle row view.
     *
     * @param row       The row view (from settings_toggle_row.xml)
     * @param labelRes  String resource for the main label
     * @param subtitleRes String resource for the subtitle, or 0 to hide it
     */
    private fun configureToggleRow(row: View, labelRes: Int, subtitleRes: Int) {
        row.findViewById<TextView>(R.id.text_setting_label)?.setText(labelRes)
        val subtitle = row.findViewById<TextView>(R.id.text_setting_subtitle)
        if (subtitleRes != 0) {
            subtitle?.setText(subtitleRes)
            subtitle?.visibility = View.VISIBLE
        } else {
            subtitle?.visibility = View.GONE
        }
    }

    // ─── Settings Load & Save ────────────────────────────────────────────────

    /**
     * Loads the current settings and populates the UI.
     * Then sets up click/toggle listeners for each row.
     */
    private fun loadAndPopulate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            populateUI(settings)
            setupListeners()
        }
    }

    /** Populates all UI elements with values from [settings]. */
    private fun populateUI(settings: AppSettings) {
        textDisplayNameValue.text = settings.effectiveDisplayName.ifEmpty {
            getString(R.string.setting_display_name_system_default)
        }
        setToggle(rowAirPlay,      settings.airPlayEnabled)
        setToggle(rowMiracast,     settings.miracastEnabled)
        setToggle(rowCast,         settings.castEnabled)
        setToggle(rowDlna,         settings.dlnaEnabled)
        setToggle(rowLowBandwidth, settings.lowBandwidthMirroring)
        setToggle(rowAudioOnly, settings.airPlayAudioOnly)
        setToggle(rowSenderHandoff, settings.replaceCurrentSender)
        setToggle(rowMirrorAudio,  settings.mirrorAudioEnabled)
        setToggle(rowIdlePreview, settings.idleVideoPreview)
        setToggle(rowAutoFullscreen, settings.autoFullscreen)
        setToggle(rowAutoAudioMode, settings.autoAudioMode)
        setToggle(rowStartOnLaunch, settings.startOnLaunch)
        setToggle(rowStartOnBoot,  settings.startOnBoot)
        setToggle(rowDebugOverlay, settings.showDebugOverlay)
    }

    private fun setToggle(row: View, value: Boolean) {
        row.findViewById<SwitchCompat>(R.id.switch_setting)?.isChecked = value
    }

    /**
     * Sets up click and toggle listeners for all settings rows.
     * Each listener immediately persists the change via [SettingsRepository.update].
     *
     * No "Save" button is needed — settings are saved on every interaction.
     * A restart prompt is shown after protocol-affecting changes.
     */
    private fun setupListeners() {
        rowDisplayName.setOnClickListener { showDisplayNameDialog() }

        val receiverRows = mapOf(
            Protocol.AIRPLAY to rowAirPlay, Protocol.MIRACAST to rowMiracast,
            Protocol.CAST to rowCast, Protocol.DLNA to rowDlna
        )
        receiverRows.forEach { (protocol, row) ->
            if (protocol == Protocol.CAST && BuildConfig.FLAVOR != "googletv") {
                row.visibility = View.GONE
                return@forEach
            }
            setToggleListener(row) { enabled ->
                setToggle(row, !enabled)
                (activity as? AirReceiverActivity)?.setReceiverEnabled(protocol, enabled)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                receiverRows.forEach { (protocol, row) -> setToggle(row, settings.isEnabled(protocol)) }
                val format = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.ember.airreceiver.airplay.mirror.MirrorCapabilities.resolve(receiverOptions.context.applicationContext, settings)
                }
                receiverOptions.render(settings, format)
            }
        }
        setToggleListener(rowLowBandwidth) { enabled -> save { it.copy(lowBandwidthMirroring = enabled) } }
        setToggleListener(rowAudioOnly) { enabled -> saveAndRestart { it.copy(airPlayAudioOnly = enabled) } }
        setToggleListener(rowSenderHandoff) { enabled -> save { it.copy(replaceCurrentSender = enabled) } }
        setToggleListener(rowMirrorAudio)  { enabled -> saveAndRestart { it.copy(mirrorAudioEnabled = enabled) } }
        setToggleListener(rowIdlePreview) { enabled -> save { it.copy(idleVideoPreview = enabled) } }
        setToggleListener(rowAutoFullscreen) { enabled -> save { it.copy(autoFullscreen = enabled) } }
        setToggleListener(rowAutoAudioMode) { enabled -> save { it.copy(autoAudioMode = enabled) } }
        setToggleListener(rowStartOnLaunch) { enabled -> save { it.copy(startOnLaunch = enabled) } }
        setToggleListener(rowStartOnBoot)  { enabled -> save { it.copy(startOnBoot = enabled) } }
        setToggleListener(rowDebugOverlay) { enabled ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (settingsRepository.update { it.copy(showDebugOverlay = enabled) }) {
                    StreamStats.overlayEnabled = enabled
                } else {
                    setToggle(rowDebugOverlay, settingsRepository.settingsFlow.first().showDebugOverlay)
                }
            }
        }
        requireView().findViewById<View>(R.id.row_safe_area).setOnClickListener { showSafeArea() }
        rowDiagnostics.setOnClickListener { openDiagnostics() }
        rowPrivacy.setOnClickListener { openPrivacyPolicy() }
        rowReset.setOnClickListener { showResetConfirmation() }
    }

    private fun showSafeArea() {
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = settingsRepository.settingsFlow.first().safeAreaPercent
            val context = requireContext()
            val density = resources.displayMetrics.density
            val layout = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val preview = android.widget.FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            val border = View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((3 * density).toInt().coerceAtLeast(1), context.getColor(R.color.text_primary))
                    setColor(context.getColor(R.color.background_dark))
                }
            }
            val radio = android.widget.RadioGroup(context)
            val amounts = listOf(0, 3, 5, 8)
            var selected = saved
            preview.addView(border, android.widget.FrameLayout.LayoutParams(-1, -1))
            layout.addView(preview, android.widget.LinearLayout.LayoutParams(-1, (125 * density).toInt()))
            layout.addView(radio)
            fun previewArea(amount: Int) {
                val scale = 1f - amount * 0.02f
                border.scaleX = scale
                border.scaleY = scale
            }
            for (amount in amounts) {
                val item = android.widget.RadioButton(context).apply {
                    id = View.generateViewId()
                    text = if (amount == 0) getString(R.string.ember_safe_area_reset)
                    else getString(R.string.ember_safe_area_amount, amount)
                    setOnClickListener { selected = amount; previewArea(amount) }
                }
                radio.addView(item)
                if (amount == saved) radio.check(item.id)
            }
            previewArea(saved)
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.ember_safe_area)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        settingsRepository.update { it.copy(safeAreaPercent = selected) }
                    }
                }
                .show()
        }
    }

    /**
     * Opens the diagnostics screen.
     *
     * WHY the transaction is on the back stack: the owner walks through the
     * settings with the remote control and wants to come back to the settings with
     * the back button — not to leave the app and stop the receiver.
     *
     * WHY the content container is replaced: the diagnostics screen is a screen, not
     * a dialog. The navigation panel of the activity stays visible, so the owner
     * always sees where he is.
     */
    private fun openDiagnostics() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, DiagnosticsFragment())
            .addToBackStack(null)
            .commit()
        Logger.d("Settings: opening the diagnostics screen")
    }

    /**
     * Opens the privacy policy.
     *
     * WHY this entry exists: Google Play requires a privacy policy that is reachable
     * **from inside the app** — a policy that only lives in the store listing does
     * not pass the review.
     *
     * WHY the fallback: many televisions have no browser at all. When nothing can
     * open the address, the address itself is shown in a dialog, so the viewer can
     * read it on another device instead of pressing a button that does nothing.
     */
    private fun openPrivacyPolicy() {
        val url = getString(R.string.privacy_policy_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            Logger.d("Settings: opening the privacy policy in an external app")
        } catch (e: ActivityNotFoundException) {
            Logger.w("Settings: no app can open the privacy policy — showing the address")
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.setting_privacy)
                .setMessage(getString(R.string.privacy_policy_no_browser, url))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun setToggleListener(row: View, onChanged: (Boolean) -> Unit) {
        // The whole row is clickable (better TV UX than just the Switch widget)
        row.setOnClickListener {
            val switch = row.findViewById<SwitchCompat>(R.id.switch_setting) ?: return@setOnClickListener
            val newValue = !switch.isChecked
            switch.isChecked = newValue
            onChanged(newValue)
        }
    }

    /**
     * Saves an updated [AppSettings] via the repository.
     * Runs in a coroutine so it doesn't block the UI thread.
     *
     * @param transform A function that takes the current settings and returns updated settings.
     */
    private fun save(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            Logger.d("Settings saved")
        }
    }

    /**
     * Saves a setting that the AirPlay receiver only reads at startup (mirror-audio, PIN auth), then
     * restarts the service so the change applies immediately instead of on the next manual restart.
     */
    private fun saveAndRestart(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            ServiceController.restart(requireContext())
            Logger.i("Settings saved — restarting receivers to apply")
        }
    }

    /**
     * Shows a dialog allowing the user to edit the AirPlay display name.
     *
     * WHY: The display name is what appears in the macOS/iOS AirPlay picker.
     * Changing it is infrequent but important for multi-TV households.
     *
     * TV UX notes:
     * - The EditText is pre-filled with the current name (empty = system default)
     * - Max length is enforced to [AppSettings.DISPLAY_NAME_MAX_LENGTH] (63 chars, mDNS limit)
     * - "OK" saves the new name; "Reset to default" clears to "" (system name); "Cancel" = no-op
     * - Name trimming is applied on save — pure-whitespace names are treated as blank
     *
     * Collision detection: Android's NsdManager automatically appends " (2)", " (3)" etc. if
     * another device on the network already uses the same mDNS name. This is transparent to
     * the user at save-time; the actual registered name is logged at registration.
     */
    private fun showDisplayNameDialog() {
        val currentName = viewLifecycleOwner.lifecycleScope.run {
            // Read directly from the displayed value (already loaded)
            val displayed = textDisplayNameValue.text?.toString() ?: ""
            if (displayed == getString(R.string.setting_display_name_system_default)) "" else displayed
        }

        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = getString(R.string.setting_display_name_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(AppSettings.DISPLAY_NAME_MAX_LENGTH))
            setSingleLine(true)
            // Move cursor to end so user can append rather than overwrite
            setSelection(currentName.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_display_name)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text?.toString()?.trim() ?: ""
                // The advertised mDNS name is fixed at registration time, so the receivers
                // have to restart for the new name to reach the AirPlay picker.
                saveAndRestart { it.copy(displayName = newName) }
                textDisplayNameValue.text = newName.ifEmpty {
                    getString(R.string.setting_display_name_system_default)
                }
                // WHY the name is not logged: it is the name the owner gave their
                // television, and a log excerpt may end up in a public issue tracker.
                Logger.i("Display name updated (${newName.length} chars)")
            }
            .setNeutralButton(R.string.setting_display_name_reset) { _, _ ->
                saveAndRestart { it.copy(displayName = "") }
                textDisplayNameValue.text = getString(R.string.setting_display_name_system_default)
                Logger.i("Display name reset to system default")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showResetConfirmation() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_reset_defaults)
            .setMessage(R.string.setting_reset_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.setting_reset_action) { _, _ -> resetSettings() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
        dialog.show()
    }

    private fun resetSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val previousSettings = settingsRepository.resetToDefaults()
            if (previousSettings == null) {
                Toast.makeText(requireContext(), R.string.setting_reset_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            val defaults = AppSettings.DEFAULT
            populateUI(defaults)
            StreamStats.overlayEnabled = defaults.showDebugOverlay
            if (previousSettings.copy(startOnBoot = defaults.startOnBoot,
                    showDebugOverlay = defaults.showDebugOverlay) != defaults) {
                (activity as? AirReceiverActivity)?.restartRunningReceivers()
            }
        }
    }
}
