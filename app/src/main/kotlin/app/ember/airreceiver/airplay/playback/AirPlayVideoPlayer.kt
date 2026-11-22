// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.playback

import android.media.AudioAttributes
import app.ember.airreceiver.util.PlaybackFocus
import app.ember.airreceiver.util.hasAudioTrack
import android.media.MediaPlayer
import android.view.Surface
import app.ember.airreceiver.util.Logger

/** The two start-position forms sent by AirPlay controllers. */
data class MediaStartPosition(val fraction: Double = 0.0, val seconds: Double? = null) {
    internal fun milliseconds(duration: Int): Int {
        val requested = seconds?.times(1000) ?: (fraction.coerceIn(0.0, 1.0) * duration)
        return requested.coerceIn(0.0, if (duration > 0) duration.toDouble() else Int.MAX_VALUE.toDouble()).toInt()
    }
}

enum class PlaybackPhase { PREPARING, BUFFERING, PLAYING, PAUSED, ENDED, FAILED }

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
    val phase: PlaybackPhase = PlaybackPhase.PREPARING,
    val bufferedUntilSec: Double? = null,
    val seekable: Boolean = false,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * such as a browser or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onTerminal: (PlaybackPhase) -> Unit = {},
) {
    private var wantsPlayback = true
    private var needsFocus = true
    private val focus = PlaybackFocus { audible ->
        synchronized(this) {
            val player = mp
            if (prepared && player != null) runCatching {
                if (audible && wantsPlayback && focusGranted()) player.start()
                else if (player.isPlaying) player.pause()
            }
        }
    }
    private fun focusGranted(explicitResume: Boolean = false): Boolean = !needsFocus || focus.acquire(explicitResume)

    private var mp: MediaPlayer? = null
    @Volatile private var prepared = false
    private var startPosition = MediaStartPosition()
    private var buffering = false
    private var bufferPercent: Int? = null
    private var seekAllowed = true
    @Volatile private var audioOnly = false

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    @Synchronized
    fun play(url: String, startPositionFraction: Double) {
        play(url, MediaStartPosition(fraction = startPositionFraction))
    }

    @Synchronized
    internal fun play(url: String, position: MediaStartPosition) {
        start(url, position, audioOnly = false)
    }

    /**
     * Starts playing an audio-only stream [url].
     *
     * WHY: an audio stream must not be rendered onto the video surface — the decoder
     * would show a black rectangle over the whole screen. The DLNA renderer uses this
     * for control points that announce an audio item.
     */
    @Synchronized
    fun playAudio(url: String) {
        start(url, MediaStartPosition(), audioOnly = true)
    }

    @Synchronized
    private fun start(url: String, position: MediaStartPosition, audioOnly: Boolean) {
        release()
        wantsPlayback = true
        this.audioOnly = audioOnly
        startPosition = position
        buffering = false
        bufferPercent = null
        seekAllowed = true
        val player = MediaPlayer()
        mp = player
        prepared = false
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        if (audioOnly) AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_MOVIE
                    )
                    .build()
            )
            if (!audioOnly) surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { complete(it, PlaybackPhase.ENDED) }
            player.setOnBufferingUpdateListener { source, percent ->
                synchronized(this) {
                    if (mp === source) bufferPercent = percent.coerceIn(0, 100)
                }
            }
            player.setOnInfoListener { source, what, _ ->
                synchronized(this) {
                    if (mp === source) when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
                        MediaPlayer.MEDIA_INFO_NOT_SEEKABLE -> seekAllowed = false
                    }
                }
                false
            }
            player.setOnErrorListener { failed, what, extra ->
                complete(failed, PlaybackPhase.FAILED)
                Logger.e("AirPlay video error what=$what extra=$extra")
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure {
            Logger.e("AirPlay video: setup failed", it)
            complete(player, PlaybackPhase.FAILED)
        }
    }

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        // onPrepared fires on MediaPlayer's own thread and can race release()/a new play(): if the
        // player was released (or replaced) while preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        // The Surface usually doesn't exist at setDataSource() time (the Activity creates it only
        // after CONNECTED) — attach it now that prepare has completed.
        if (!audioOnly) surfaceProvider()?.let { runCatching { player.setSurface(it) } }
        val offset = startPosition.milliseconds(runCatching { player.duration }.getOrDefault(0))
        if (offset > 0) runCatching { player.seekTo(offset) }
        needsFocus = audioOnly || player.hasAudioTrack()
        prepared = true
        if (wantsPlayback && focusGranted()) runCatching { player.start() }
        onReady()
        Logger.i("AirPlay video: prepared dur=${runCatching { player.duration }.getOrDefault(0)}ms → playing")
    }

    private fun complete(player: MediaPlayer, phase: PlaybackPhase) {
        synchronized(this) {
            if (mp !== player) return
            release()
            onTerminal(phase)
            onEnded()
        }
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        wantsPlayback = rate > 0f
        if (!wantsPlayback) focus.release()
        val player = mp ?: return
        if (!prepared) return
        runCatching {
            if (rate <= 0f) { if (player.isPlaying) player.pause() }
            else { if (!player.isPlaying && focusGranted(explicitResume = true)) player.start() }
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        if (!prepared || !seekAllowed || !positionSec.isFinite()) return
        val player = mp ?: return
        runCatching {
            val offset = MediaStartPosition(seconds = positionSec).milliseconds(player.duration)
            player.seekTo(offset)
        }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? {
        val player = mp ?: return null
        if (!prepared) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        val dur = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        val duration = dur / 1000.0
        val phase = when {
            buffering -> PlaybackPhase.BUFFERING
            playing -> PlaybackPhase.PLAYING
            else -> PlaybackPhase.PAUSED
        }
        return PlaybackInfo(
            duration, pos.coerceAtLeast(0) / 1000.0, if (playing && !buffering) 1.0 else 0.0,
            readyToPlay = true, phase = phase,
            bufferedUntilSec = bufferPercent?.takeIf { dur > 0 }?.let { duration * it / 100 },
            seekable = seekAllowed && dur > 0
        )
    }

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    @Synchronized
    fun attachSurface() {
        surfaceProvider()?.let { runCatching { mp?.setSurface(it) } }
    }

    @Synchronized
    fun release() {
        wantsPlayback = false
        focus.release()
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
        prepared = false
    }
}
