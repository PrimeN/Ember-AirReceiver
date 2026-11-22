package app.ember.airreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Test

class WfdTransportStreamTest {
    @Test
    fun stripsHeadersAndIgnoresDuplicatesAcrossSequenceWrap() {
        WfdTransportStream().use { stream ->
            val packet = ByteArray(12 + 188)
            packet[0] = 0x80.toByte()
            packet[1] = 33
            packet[2] = -1
            packet[3] = -1
            packet[12] = 0x47
            packet[13] = 1
            stream.appendPacket(packet, packet.size)
            stream.appendPacket(packet, packet.size)
            packet[2] = 0
            packet[3] = 0
            packet[13] = 2
            stream.appendPacket(packet, packet.size)
            val frames = ByteArray(376)
            assertEquals(376, stream.readAt(0, frames, 0, frames.size))
            assertEquals(1, frames[1].toInt())
            assertEquals(2, frames[189].toInt())
        }
    }
}
