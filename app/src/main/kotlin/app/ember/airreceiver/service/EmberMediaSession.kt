package app.ember.airreceiver.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import app.ember.airreceiver.R
import app.ember.airreceiver.airplay.nowplaying.DacpClient
import app.ember.airreceiver.airplay.nowplaying.NowPlayingInfo
import app.ember.airreceiver.airplay.playback.PlaybackInfo
import app.ember.airreceiver.airplay.playback.PlaybackPhase
import app.ember.airreceiver.util.SafeBitmapDecoder

/** System media controls for the receiver service's current AirPlay playback. */
internal class EmberMediaSession(
    context: Context,
    private val rate: (Float) -> Unit,
    private val seek: (Double) -> Unit,
    private val stop: () -> Unit,
    private val send: (String) -> Unit
) {
    private val appName = context.getString(R.string.app_name)
    private val session = MediaSession(context, appName)
    private var activeUrl: PlaybackInfo? = null
    private var currentAudio: NowPlayingInfo? = null
    private var canControlAudio = false

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (activeUrl != null) rate(1f) else if (canControlAudio) send("play")
            }

            override fun onPause() {
                if (activeUrl != null) rate(0f) else if (canControlAudio) send("pause")
            }

            override fun onSeekTo(pos: Long) {
                if (activeUrl?.seekable == true) seek(pos / 1000.0)
            }

            override fun onStop() {
                if (activeUrl != null) stop()
            }

            override fun onSkipToNext() {
                if (canControlAudio) send(DacpClient.CMD_NEXT)
            }

            override fun onSkipToPrevious() {
                if (canControlAudio) send(DacpClient.CMD_PREV)
            }
        })
    }

    fun update(url: PlaybackInfo?, audio: NowPlayingInfo?, canControl: Boolean) {
        val video = url?.takeIf { it.phase != PlaybackPhase.ENDED && it.phase != PlaybackPhase.FAILED && !externalPlaybackActive }
        val track = audio?.takeIf { it.notice == null && video == null && !externalPlaybackActive }
        if (video == null && track == null) {
            activeUrl = null
            currentAudio = null
            canControlAudio = false
            if (session.isActive) session.isActive = false
            return
        }
        if (track != currentAudio || video?.durationSec != activeUrl?.durationSec) {
            session.setMetadata(MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: appName)
                putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artist ?: track?.senderName)
                putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album)
                track?.artwork?.let { bytes ->
                    SafeBitmapDecoder.decode(bytes)?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                }
                if (video != null && video.durationSec > 0) {
                    putLong(MediaMetadata.METADATA_KEY_DURATION, (video.durationSec * 1000).toLong())
                }
            }.build())
        }
        activeUrl = video
        currentAudio = track
        canControlAudio = track != null && canControl
        val state = when (video?.phase) {
            PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING -> PlaybackState.STATE_BUFFERING
            PlaybackPhase.PAUSED -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        var actions = if (video != null || canControlAudio) {
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        } else 0L
        if (video != null) actions = actions or PlaybackState.ACTION_STOP
        if (video?.seekable == true) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (canControlAudio) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(state, video?.let { (it.positionSec * 1000).toLong() } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build())
        if (!session.isActive) session.isActive = true
    }

    fun release() = session.release()

    companion object {
        @Volatile var externalPlaybackActive = false
    }
}
