// Licensed under the Apache License, Version 2.0 — see LICENSE.
package app.ember.airreceiver.airplay.timing

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises sender-to-monotonic mapping without a network connection or wall clock.
 * A matching probe establishes the timebase for both mirror timestamp epochs.
 */
class AirPlayNtpClientTest {
    private val request = ByteBuffer.allocate(32).putLong(24, 7L)
    private val sender = 4_000_000_000L shl 32
    private val response = ByteBuffer.allocate(32).apply {
        put(1, 0xD3.toByte())
        putLong(8, 7L)
        putLong(16, sender)
        putLong(24, sender)
    }

    @Test
    fun `maps a valid reply and accepts both mirror timestamp epochs`() {
        val sample = checkNotNull(AirPlayNtpClient.timingSample(request.array(), response.array(), 0, 10_000_000))
        assertEquals(10_000_000L, sample.roundTripNs)
        assertEquals(25_000_000L, AirPlayNtpClient.mirrorPresentationNanos(sample, sender, 10_000_000))
        val unixTimestamp = sender - (2_208_988_800L shl 32)
        assertEquals(25_000_000L, AirPlayNtpClient.mirrorPresentationNanos(sample, unixTimestamp, 10_000_000))
        assertNull(AirPlayNtpClient.mirrorPresentationNanos(sample, sender, 11_000_000_000))
    }

    @Test
    fun `ignores truncated mismatched and high latency timing replies`() {
        assertNull(AirPlayNtpClient.timingSample(request.array(), ByteArray(16), 0, 10_000_000))
        assertNull(AirPlayNtpClient.timingSample(request.array(), response.array(), 0, 160_000_000))
        response.putLong(8, 8L)
        assertNull(AirPlayNtpClient.timingSample(request.array(), response.array(), 0, 10_000_000))
    }

    @Test
    fun `computes subsecond deltas across the NTP seconds rollover`() {
        val before = -0x4000_0000L
        val after = 0x4000_0000L
        assertEquals(500_000_000L, AirPlayNtpClient.fixedPointDeltaNanos(after - before))
        assertEquals(-500_000_000L, AirPlayNtpClient.fixedPointDeltaNanos(before - after))
    }
}
