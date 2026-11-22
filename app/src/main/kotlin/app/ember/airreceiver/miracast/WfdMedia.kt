package app.ember.airreceiver.miracast

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import app.ember.airreceiver.util.PlaybackFocus
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** A live MPEG transport stream with a bounded seek window for the platform extractor. */
internal class WfdTransportStream : MediaDataSource() {
    private val buffer = ByteArray(2 * 1024 * 1024)
    private val lock = Object()
    private var end = 0L
    private var closed = false

    fun append(bytes: ByteArray, offset: Int, count: Int) = synchronized(lock) {
        if (!closed) {
            val index = (end % buffer.size).toInt()
            val first = minOf(count, buffer.size - index)
            bytes.copyInto(buffer, index, offset, offset + first)
            bytes.copyInto(buffer, 0, offset + first, offset + count)
            end += count
            lock.notifyAll()
        }
    }

    override fun readAt(position: Long, target: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
        if (size == 0) return 0
        while (!closed && position >= end) lock.wait()
        if (closed || position < 0 || position < end - buffer.size) return -1
        val count = minOf(size.toLong(), end - position).toInt()
        val index = (position % buffer.size).toInt()
        val first = minOf(count, buffer.size - index)
        buffer.copyInto(target, offset, index, index + first)
        buffer.copyInto(target, offset + first, 0, count - first)
        count
    }

    override fun getSize() = -1L
    override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
}

internal class WfdMedia(
    private val peer: InetAddress,
    private val surface: () -> Surface?,
    private val rendered: () -> Unit,
    private val failed: () -> Unit
) {
    private val socket = DatagramSocket(0)
    val port = socket.localPort
    private val stream = WfdTransportStream()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val focus = PlaybackFocus { allowed ->
        player?.setVolume(if (allowed) 1f else 0f, if (allowed) 1f else 0f)
    }
    @Volatile private var closed = false

    fun start() {
        Thread({
            try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.soTimeout = 15000
                while (!closed) {
                    packet.length = packet.data.size
                    socket.receive(packet)
                    if (packet.address != peer) continue
                    val data = packet.data
                    val size = packet.length
                    if (size < 12 || data[0].toInt() ushr 6 and 3 != 2 || data[1].toInt() and 127 != 33) continue
                    var start = 12 + 4 * (data[0].toInt() and 15)
                    if (data[0].toInt() and 16 != 0) {
                        if (start + 4 > size) continue
                        val words = (data[start+2].toInt() and 255) * 256 + (data[start+3].toInt() and 255)
                        start += 4 + 4 * words
                    }
                    val padding = if (data[0].toInt() and 32 != 0) data[size-1].toInt() and 255 else 0
                    val count = size - start - padding
                    if (count > 0 && count % 188 == 0 && data[start] == 0x47.toByte()) stream.append(data, start, count)
                }
            } catch (e: java.io.IOException) {
                if (!closed) failed()
            }
        }, "Ember mirror media").start()
        main.post(object : Runnable {
            private var attempts = 0
            override fun run() {
                if (closed) return
                val output = surface()
                if (output == null || !output.isValid) {
                    if (++attempts < 100) main.postDelayed(this, 100) else failed()
                    return
                }
                try {
                    player = MediaPlayer().also { media ->
                        media.setSurface(output)
                        media.setOnInfoListener { _, what, _ ->
                            if (!closed && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered()
                            false
                        }
                        media.setOnPreparedListener {
                            if (!closed) {
                                val gain = if (focus.acquire()) 1f else 0f
                                it.setVolume(gain, gain)
                                it.start()
                            }
                        }
                        media.setOnErrorListener { _, _, _ -> if (!closed) failed(); true }
                        media.setDataSource(stream)
                        media.prepareAsync()
                    }
                } catch (e: Exception) { if (!closed) failed() }
            }
        })
    }

    fun close() {
        closed = true
        socket.close()
        stream.close()
        main.post {
            focus.release()
            player?.release()
            player = null
        }
    }
}
