package app.ember.airreceiver.ui

import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.ember.airreceiver.R
import app.ember.airreceiver.airplay.StreamStats
import app.ember.airreceiver.settings.AirPlayPinPolicy
import app.ember.airreceiver.settings.AppSettings
import app.ember.airreceiver.settings.MirrorFormat
import app.ember.airreceiver.settings.MirrorResolution

internal class ReceiverOptions(root: View, private val save: ((AppSettings) -> AppSettings) -> Unit) {
    val context = root.context
    private val pin: Button = root.findViewById(R.id.row_pin_policy)
    private val resolution: Button = root.findViewById(R.id.row_mirror_resolution)
    private val fps: Button = root.findViewById(R.id.row_mirror_fps)
    private val limits: TextView = root.findViewById(R.id.text_mirror_format)
    private val rates = listOf(0, 24, 30, 60, 120)
    private val pinLabels = mapOf(
        AirPlayPinPolicy.OFF to R.string.setting_pin_off,
        AirPlayPinPolicy.REMEMBER to R.string.setting_pin_remember,
        AirPlayPinPolicy.EACH_SESSION to R.string.setting_pin_each_session
    )

    fun render(settings: AppSettings, format: MirrorFormat) {
        val policy = settings.effectivePinPolicy
        val selected = settings.effectiveMirrorResolution
        val currentSize = if (selected == MirrorResolution.CUSTOM) {
            dimensions(settings.customMirrorWidth, settings.customMirrorHeight)
        } else resolutionLabel(selected)
        pin.text = context.getString(R.string.setting_pin_policy, context.getString(pinLabels.getValue(policy)))
        resolution.text = context.getString(R.string.setting_mirror_resolution, currentSize)
        fps.text = context.getString(R.string.setting_mirror_fps, rateLabel(settings.maxMirrorFps))
        limits.text = buildList {
            add(context.getString(R.string.setting_mirror_effective, format.width, format.height, format.fps))
            if (settings.lowBandwidthMirroring) add(context.getString(R.string.setting_mirror_low_bandwidth_note))
            if (StreamStats.videoAdvertised.isNotEmpty() && StreamStats.videoWidth > 0 && StreamStats.videoHeight > 0) {
                add(context.getString(R.string.setting_mirror_actual,
                    StreamStats.videoWidth, StreamStats.videoHeight, StreamStats.videoFps))
            }
        }.joinToString("\n")
        pin.setOnClickListener {
            val policies = AirPlayPinPolicy.entries
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val help = TextView(context).apply {
                text = context.getString(R.string.setting_pin_policy_detail)
                setPadding(32, 16, 32, 16)
            }
            content.addView(help)
            val group = android.widget.RadioGroup(context)
            content.addView(group)
            val dialog = AlertDialog.Builder(context).setTitle(R.string.setting_pin_policy_title)
                .setView(content).setNegativeButton(android.R.string.cancel, null).create()
            for (option in policies) {
                group.addView(android.widget.RadioButton(context).apply {
                    id = View.generateViewId()
                    text = context.getString(pinLabels.getValue(option))
                    isChecked = option == policy
                    setOnClickListener {
                        save { it.copy(airPlayPinPolicy = option) }
                        dialog.dismiss()
                    }
                })
            }
            dialog.show()
        }
        resolution.setOnClickListener {
            val options = MirrorResolution.entries
            pick(R.string.setting_mirror_resolution_title, options.map(::resolutionLabel), selected.ordinal) { index ->
                val choice = options[index]
                if (choice == MirrorResolution.CUSTOM) customSize(settings)
                else save { it.copy(mirrorResolution = choice) }
            }
        }
        fps.setOnClickListener {
            val options = rates.map(::rateLabel) + context.getString(R.string.setting_mirror_custom)
            val current = rates.indexOf(settings.maxMirrorFps).takeIf { it >= 0 } ?: rates.size
            pick(R.string.setting_mirror_fps_title, options, current) { index ->
                if (index == rates.size) customFps(settings.maxMirrorFps)
                else save { it.copy(maxMirrorFps = rates[index]) }
            }
        }
    }

    private fun pick(title: Int, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        AlertDialog.Builder(context).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                onSelect(index)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun dimensions(width: Int, height: Int) =
        context.getString(R.string.setting_mirror_dimensions, width, height)

    private fun resolutionLabel(option: MirrorResolution) = when (option) {
        MirrorResolution.AUTO -> context.getString(R.string.setting_mirror_auto)
        MirrorResolution.CUSTOM -> context.getString(R.string.setting_mirror_custom)
        else -> dimensions(option.width, option.height)
    }

    private fun rateLabel(value: Int) = if (value == 0) context.getString(R.string.setting_mirror_auto) else value.toString()

    private fun field(hint: Int, value: Int) = EditText(context).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        selectAll()
    }

    private fun customSize(settings: AppSettings) {
        val widthField = field(R.string.setting_mirror_custom_width, settings.customMirrorWidth)
        val heightField = field(R.string.setting_mirror_custom_height, settings.customMirrorHeight)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(widthField)
            addView(heightField)
        }
        edit(R.string.setting_mirror_resolution_title, form) {
            val width = widthField.text.toString().toIntOrNull()
            val height = heightField.text.toString().toIntOrNull()
            if (width == null || width !in 320..3840 || width % 2 != 0 ||
                height == null || height !in 240..2160 || height % 2 != 0) {
                widthField.error = context.getString(R.string.setting_mirror_invalid_size)
                false
            } else {
                save { it.copy(mirrorResolution = MirrorResolution.CUSTOM, customMirrorWidth = width, customMirrorHeight = height) }
                true
            }
        }
    }

    private fun customFps(value: Int) {
        val input = field(R.string.setting_mirror_custom_fps, value.takeIf { it > 0 } ?: 60)
        edit(R.string.setting_mirror_fps_title, input) {
            val rate = input.text.toString().toIntOrNull()
            if (rate == null || rate !in 1..120) {
                input.error = context.getString(R.string.setting_mirror_invalid_fps)
                false
            } else {
                save { it.copy(maxMirrorFps = rate) }
                true
            }
        }
    }

    private fun edit(title: Int, form: View, apply: () -> Boolean) {
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(form)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (apply()) dialog.dismiss() }
    }
}
