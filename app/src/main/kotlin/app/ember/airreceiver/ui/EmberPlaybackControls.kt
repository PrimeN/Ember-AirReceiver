package app.ember.airreceiver.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.ember.airreceiver.R
import app.ember.airreceiver.airplay.playback.PlaybackInfo
import app.ember.airreceiver.airplay.playback.PlaybackPhase
import java.util.Locale

internal class EmberPlaybackControls(context: Context) : LinearLayout(context) {
    var onRate: (Float) -> Unit = {}
    var onSeek: (Double) -> Unit = {}
    var onStop: () -> Unit = {}
    private var playback: PlaybackInfo? = null
    private val progress = TextView(context).apply { setTextColor(android.graphics.Color.WHITE) }
    private val back = button(R.string.ember_playback_behind) { seekBy(-10) }
    private val play = button(R.string.ember_playback_play) { togglePlayback() }
    private val ahead = button(R.string.ember_playback_ahead) { seekBy(10) }

    init {
        orientation = VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(context.getColor(R.color.background_dark))
        addView(progress)
        addView(LinearLayout(context).apply {
            addView(back)
            addView(play)
            addView(ahead)
        })
        visibility = GONE
    }

    fun setPlayback(info: PlaybackInfo?) {
        playback = info
        if (info == null) {
            visibility = GONE
            return
        }
        back.visibility = if (info.seekable) View.VISIBLE else View.GONE
        ahead.visibility = back.visibility
        play.setText(if (info.phase == PlaybackPhase.PLAYING) R.string.ember_playback_pause else R.string.ember_playback_play)
        progress.text = context.getString(R.string.ember_playback_progress, formatTime(info.positionSec), formatTime(info.durationSec))
        progress.visibility = if (info.durationSec > 0) View.VISIBLE else View.GONE
    }

    fun handleRemote(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || playback == null) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (event.repeatCount == 0) togglePlayback()
            KeyEvent.KEYCODE_MEDIA_PLAY -> onRate(1f)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> onRate(0f)
            KeyEvent.KEYCODE_MEDIA_STOP -> onStop()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (visibility == VISIBLE) return false
                visibility = VISIBLE
                play.requestFocus()
            }
            KeyEvent.KEYCODE_BACK -> {
                if (visibility != VISIBLE) return false
                visibility = GONE
            }
            else -> return false
        }
        return true
    }

    private fun togglePlayback() {
        onRate(if (playback?.phase == PlaybackPhase.PLAYING) 0f else 1f)
    }

    private fun seekBy(delta: Int) {
        val current = playback?.takeIf { it.seekable } ?: return
        onSeek((current.positionSec + delta).coerceIn(0.0, current.durationSec))
    }

    private fun button(label: Int, action: () -> Unit): Button = Button(context).apply {
        setText(label)
        setOnClickListener { action() }
    }

    private fun formatTime(seconds: Double): String {
        val value = seconds.toLong().coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", value / 60, value % 60)
    }
}
