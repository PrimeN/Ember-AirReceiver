package app.ember.airreceiver.airplay.rtsp

import android.content.Context
import io.mockk.mockk
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspServerHandoffTest {
    @Test
    fun `replacement is admitted before the current connection closes`() {
        var takeOver = false
        var admit = true
        var replaced = 0
        var stopped = 0
        val server = RtspServer(
            context = mockk<Context>(),
            newControl = { _, _ -> error("No socket loop needed") },
            senderHls = SenderHlsTransport({ _, _ -> }, {}),
            onStreamingStopped = { stopped++ },
            onPlaybackReplaced = { replaced++ },
            admitPlayback = { admit },
            allowHandoff = { takeOver }
        ).apply { running = true }

        Socket().use { first ->
            Socket().use { incoming ->
                assertTrue(server.claimPlayback(first))
                assertFalse(server.claimPlayback(incoming))
                takeOver = true
                admit = false
                assertFalse(server.claimPlayback(incoming))
                assertFalse(first.isClosed)
                assertEquals(0, replaced)

                admit = true
                assertTrue(server.claimPlayback(incoming))
                assertTrue(first.isClosed)
                assertTrue(server.ownsPlayback(incoming))
                assertEquals(1, replaced)
                server.finishPlayback(first)
                assertEquals(0, stopped)
                server.finishPlayback(incoming)
                assertEquals(1, stopped)
            }
        }
    }
}
