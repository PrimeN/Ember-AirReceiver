// Licensed under the Apache License, Version 2.0 — see LICENSE.
package app.ember.airreceiver.airplay.audio

import java.net.SocketAddress
import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Verifies that loss and RTSP flush boundaries cannot strand or replay queued audio. */
class AudioReorderBufferTest {
    private val queue = ArrayBlockingQueue<ByteArray>(3)
    private var now = 0L
    private val buffer = AudioReorderBuffer(queue, object : RaopControlChannel {
        override fun send(payload: ByteArray, address: SocketAddress) = Unit
    }, { now })

    @Test
    fun `missing packet expires without more network traffic`() {
        packet(100)
        packet(102)
        packet(104)
        assertArrayEquals(byteArrayOf(100), queue.poll())
        now = 59_000_000
        buffer.expireGap()
        assertNull(queue.poll())
        now = 60_000_000
        buffer.expireGap()
        assertArrayEquals(byteArrayOf(102), queue.poll())
        assertArrayEquals(byteArrayOf(104), queue.poll())
        packet(101)
        assertNull(queue.poll())
    }

    @Test
    fun `flush discards old packets and resets before playback across sequence wrap`() {
        packet(65532)
        packet(65534)
        buffer.flush(65535)
        packet(65534)
        packet(0)
        packet(65535)
        assertSame(AudioReorderBuffer.FLUSH, queue.poll())
        assertArrayEquals(byteArrayOf(-1), queue.poll())
        assertArrayEquals(byteArrayOf(0), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `queue overflow preserves the pending flush`() {
        buffer.flush(null)
        for (seq in 0..5) packet(seq)
        assertSame(AudioReorderBuffer.FLUSH, queue.poll())
        assertArrayEquals(byteArrayOf(4), queue.poll())
        assertArrayEquals(byteArrayOf(5), queue.poll())
    }

    private fun packet(sequence: Int) {
        val data = ByteArray(AudioReorderBuffer.RTP_HEADER + 1)
        data[2] = (sequence ushr 8).toByte()
        data[3] = sequence.toByte()
        data[data.lastIndex] = sequence.toByte()
        buffer.handleRtpPacket(data, 0, data.size)
    }
}
