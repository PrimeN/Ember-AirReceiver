// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.mirror

import android.view.Surface
import app.ember.airreceiver.airplay.pairing.MirrorCrypto
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises a rejected decoder setup and an IDR retry on the same mirror connection.
 * This keeps the network and queue path real while controlling the Android decoder outcome.
 * Run with `./gradlew :test-runner:test --tests '*MirrorDecoderRecoveryTest'`.
 */
class MirrorDecoderRecoveryTest {
    @Test
    fun `a later IDR can recover after decoder setup fails`() = runBlocking {
        val surface = mockk<Surface>()
        every { surface.isValid } returns true
        val setupAttempts = AtomicInteger()
        val submitted = CountDownLatch(1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)
        mockkConstructor(VideoDecoder::class)
        try {
            every { anyConstructed<VideoDecoder>().initialize(any(), any(), any(), any()) } answers {
                if (setupAttempts.incrementAndGet() == 1) error("Decoder temporarily unavailable")
            }
            every { anyConstructed<VideoDecoder>().drainPendingOutput() } answers { }
            every { anyConstructed<VideoDecoder>().release() } answers { }
            every { anyConstructed<VideoDecoder>().isHealthy } returns true
            every { anyConstructed<VideoDecoder>().decodeNalUnit(match { it.contentEquals(idr) }, any()) } answers {
                submitted.countDown()
                true
            }

            val key = ByteArray(16)
            val secret = ByteArray(32)
            val connectionId = 1L
            val sender = MirrorCrypto.streamCipher(key, secret, connectionId)
            val server = MirrorStreamServer(key, secret, connectionId, { surface })
            val job = SupervisorJob()
            server.start(CoroutineScope(job + Dispatchers.IO))
            try {
                Socket("127.0.0.1", server.dataPort).use { socket ->
                    val output = socket.getOutputStream()
                    val config = byteArrayOf(1, 0x64, 0, 0x28, 0xFF.toByte(), 1, 0, 1, 0x67, 1, 0, 1, 0x68)
                    output.writePacket(1, config)
                    output.writePacket(0, sender.update(byteArrayOf(0, 0, 0, 1, 0x41)))
                    output.writePacket(0, sender.update(idr))
                    assertTrue("The later IDR must reach the decoder", submitted.await(5, TimeUnit.SECONDS))
                    assertEquals(2, setupAttempts.get())
                }
            } finally {
                server.stop()
                job.cancelAndJoin()
            }
        } finally {
            unmockkConstructor(VideoDecoder::class)
        }
    }

    private fun OutputStream.writePacket(type: Int, bytes: ByteArray) {
        val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(bytes.size).putShort(type.toShort())
        write(header.array())
        write(bytes)
        flush()
    }
}
