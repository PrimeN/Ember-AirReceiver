package app.ember.airreceiver.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.ember.airreceiver.R
import app.ember.airreceiver.airplay.playback.PlaybackInfo
import app.ember.airreceiver.airplay.playback.PlaybackTrackKind
import app.ember.airreceiver.airplay.playback.PlaybackPhase
import java.util.Locale

internal class EmberPlaybackControls(context: Context) : LinearLayout(context) {
    var onTrack: (Long, PlaybackTrackKind, Int?) -> Unit = { _, _, _ -> }
    var onRate: (Float) -> Unit = {}
    var onSeek: (Double) -> Unit = {}
    var onStop: () -> Unit = {}
    private var playback: PlaybackInfo? = null
    private val tracks = button(R.string.ember_playback_tracks) { showTracks() }
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
            addView(tracks)
        })
        visibility = GONE
    }

    fun setPlayback(info: PlaybackInfo?) {
        playback = info
        if (info == null) {
            visibility = GONE
            return
        }
        tracks.visibility = if (info.tracks.isEmpty()) GONE else VISIBLE
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

    private fun showTracks() {
        val info = playback ?: return
        val options = buildList {
            for (kind in PlaybackTrackKind.entries) {
                val matching = info.tracks.filter { it.kind == kind }
                if (matching.isEmpty()) continue
                val defaultLabel = if (kind == PlaybackTrackKind.AUDIO) R.string.ember_audio_automatic
                    else R.string.ember_captions_off
                add(Triple(context.getString(defaultLabel), kind, null))
                matching.forEach { track ->
                    val language = Locale.forLanguageTag(track.language).getDisplayLanguage().ifBlank {
                        context.getString(R.string.ember_track_number, track.index + 1)
                    }
                    val label = if (kind == PlaybackTrackKind.AUDIO) R.string.ember_audio_track
                        else R.string.ember_caption_track
                    add(Triple(context.getString(label, language), kind, track.index))
                }
            }
        }
        if (options.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.ember_playback_tracks)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val (_, kind, index) = options[which]
                onTrack(info.trackSession, kind, index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
