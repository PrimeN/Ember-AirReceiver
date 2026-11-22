package app.ember.airreceiver.util

import android.media.AudioManager

/** Each audible session owns a focus request from its first sample until pause or teardown. */
internal class PlaybackFocus(private val onChange: (Boolean) -> Unit = {}) {
    companion object {
        lateinit var manager: AudioManager
    }

    @Volatile private var granted = false
    private var needsNewRequest = false
    private var listener: AudioManager.OnAudioFocusChangeListener? = null

    @Synchronized
    fun acquire(explicitResume: Boolean = false): Boolean {
        if (explicitResume && needsNewRequest) release()
        if (listener != null) return granted
        val request = object : AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(change: Int) {
                val audible = change == AudioManager.AUDIOFOCUS_GAIN
                synchronized(this@PlaybackFocus) {
                    if (listener !== this) return
                    if (change == AudioManager.AUDIOFOCUS_LOSS) needsNewRequest = true
                    if (audible && needsNewRequest) return
                    granted = audible
                }
                onChange(audible)
            }
        }
        listener = request
        granted = manager.requestAudioFocus(request, AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        needsNewRequest = !granted
        return granted
    }

    @Synchronized
    fun release() {
        val old = listener
        listener = null
        needsNewRequest = false
        granted = false
        if (old != null) manager.abandonAudioFocus(old)
    }
}
