package app.ember.airreceiver.cast

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.ember.airreceiver.R
import app.ember.airreceiver.service.EmberMediaSession
import app.ember.airreceiver.service.PlaybackOwnership
import app.ember.airreceiver.settings.SettingsRepository
import app.ember.airreceiver.util.FetchableUrlPolicy
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.MediaManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Owns the playback surface and publishes transport controls for the current sender load. */
class EmberPlaybackActivity : FragmentActivity() {
    private lateinit var container: FrameLayout
    private lateinit var session: MediaSessionCompat
    private var manager: MediaManager? = null
    private var video: VideoView? = null
    private var pending: TaskCompletionSource<MediaLoadRequestData>? = null
    private var playWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.background_dark)) }
        setContentView(container)
        session = MediaSessionCompat(this, "Ember playback")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                playWhenReady = true
                video?.start()
                publish(PlaybackStateCompat.STATE_PLAYING)
            }
            override fun onPause() {
                playWhenReady = false
                video?.pause()
                publish(PlaybackStateCompat.STATE_PAUSED)
            }
            override fun onSeekTo(pos: Long) {
                video?.seekTo(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                publish(session.controller.playbackState?.state ?: PlaybackStateCompat.STATE_PAUSED, pos)
            }
            override fun onStop() {
                stopPlayback()
                finish()
            }
        })
        lifecycleScope.launch {
            if (!app.ember.airreceiver.service.LocalNetworkAccess.hasAccess(this@EmberPlaybackActivity) ||
                !CastReceiver.isConfigured() || !SettingsRepository(applicationContext).settingsFlow.first().castEnabled) {
                finish()
                return@launch
            }
            try {
                attachReceiver()
            } catch (error: RuntimeException) {
                Toast.makeText(this@EmberPlaybackActivity, R.string.ember_load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun attachReceiver() {
        CastReceiverContext.initInstance(applicationContext)
        val receiver = CastReceiverContext.getInstance()
        receiver.start()
        manager = receiver.mediaManager.apply {
            setSessionCompatToken(session.sessionToken)
            setMediaLoadCommandCallback(object : MediaLoadCommandCallback() {
                override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> =
                    load(request)
            })
        }
        session.isActive = true
        if (manager?.onNewIntent(intent) != true) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manager?.onNewIntent(intent)
    }

    private fun load(request: MediaLoadRequestData): Task<MediaLoadRequestData> {
        val info = request.mediaInfo
        val url = info?.contentUrl ?: info?.contentId
        if (url == null || !FetchableUrlPolicy.isFetchable(url)) {
            return Tasks.forException(IllegalArgumentException(getString(R.string.ember_load_failed)))
        }
        if (!PlaybackOwnership.acquire(this)) {
            return Tasks.forException(IllegalStateException(getString(R.string.ember_receiver_busy)))
        }
        stopPlayback(releaseOwnership = false)
        val result = TaskCompletionSource<MediaLoadRequestData>()
        pending = result
        val view = VideoView(this)
        video = view
        container.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        EmberMediaSession.externalPlaybackActive = true
        playWhenReady = request.autoplay != false
        publish(PlaybackStateCompat.STATE_BUFFERING)
        view.setOnPreparedListener {
            if (video !== view) return@setOnPreparedListener
            if (request.currentTime > 0) view.seekTo(request.currentTime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            manager?.setDataFromLoad(request)
            if (playWhenReady) view.start()
            publish(if (playWhenReady) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            pending = null
            result.trySetResult(request)
        }
        view.setOnCompletionListener {
            if (video === view) {
                stopPlayback()
                finish()
            }
        }
        view.setOnErrorListener { _, _, _ ->
            if (video === view) {
                val message = getString(R.string.ember_load_failed)
                result.trySetException(IllegalStateException(message))
                pending = null
                PlaybackOwnership.release(this)
                EmberMediaSession.externalPlaybackActive = false
                publish(PlaybackStateCompat.STATE_ERROR)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            true
        }
        view.setVideoURI(Uri.parse(url))
        return result.task
    }

    private fun publish(state: Int, position: Long = video?.currentPosition?.toLong() ?: 0L) {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions)
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f).build())
        manager?.broadcastMediaStatus()
    }

    private fun stopPlayback(releaseOwnership: Boolean = true) {
        if (releaseOwnership) PlaybackOwnership.release(this)
        EmberMediaSession.externalPlaybackActive = false
        pending?.trySetException(IllegalStateException(getString(R.string.ember_load_replaced)))
        pending = null
        val old = video
        video = null
        old?.stopPlayback()
        container.removeAllViews()
        publish(PlaybackStateCompat.STATE_STOPPED)
    }

    override fun onDestroy() {
        stopPlayback()
        manager?.setMediaLoadCommandCallback(MediaLoadCommandCallback())
        manager?.setSessionCompatToken(null)
        session.release()
        super.onDestroy()
    }
}
