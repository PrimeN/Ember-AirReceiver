// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * MirrorConfigRecordTest — tests the SPS/PPS record parser of the mirroring stream.
 *
 * WHY: the type-1 payload carries two length fields that come straight from the sender.
 * The parser used to index the payload with them unchecked and relied on an
 * out-of-bounds exception to reject a malformed record. Ember AirReceiver checks every
 * length first (SEC-01); these tests pin that behaviour.
 *
 * WHAT WE TEST:
 * - a well-formed record yields exactly its SPS and PPS bytes
 * - a record that is too short, or whose SPS/PPS length points past the end, is refused
 *   with an [IllegalArgumentException] that names the reason
 * - an empty SPS or PPS is refused (the decoder cannot be configured from it)
 *
 * HOW: [MirrorStreamServer.parseConfigRecord] is a pure function on a byte array.
 *
 * Example: `MirrorStreamServer.parseConfigRecord(record(sps, pps))`
 */
class MirrorConfigRecordTest {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte())
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    @Test
    fun `a well-formed record yields its SPS and PPS`() {
        val config = MirrorStreamServer.parseConfigRecord(record(sps, pps))

        assertArrayEquals(sps, config.sps)
        assertArrayEquals(pps, config.pps)
    }

    @Test
    fun `a record shorter than the header is refused`() {
        assertRefused(ByteArray(5), "too short")
    }

    @Test
    fun `an SPS length that points past the payload is refused`() {
        val bytes = record(sps, pps)
        bytes[6] = 0x7F                                   // SPS length ≈ 32 KB
        assertRefused(bytes, "SPS length")
    }

    @Test
    fun `a PPS length that points past the payload is refused`() {
        val bytes = record(sps, pps)
        val ppsLength = 8 + sps.size + 1
        bytes[ppsLength] = 0x10                           // PPS length ≈ 4 KB
        assertRefused(bytes, "PPS length")
    }

    @Test
    fun `a record that is cut inside the PPS is refused`() {
        val bytes = record(sps, pps)
        assertRefused(bytes.copyOf(bytes.size - 1), "PPS length")
    }

    @Test
    fun `an empty SPS is refused`() {
        assertRefused(record(ByteArray(0), pps), "SPS length")
    }

    @Test
    fun `an empty PPS is refused`() {
        assertRefused(record(sps, ByteArray(0)), "PPS length")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Builds an avcC-style record: 6 header bytes, SPS length, SPS, PPS count, PPS length, PPS. */
    private fun record(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArray(8 + sps.size + 1 + 2 + pps.size)
        out[0] = 1
        out[5] = 0xE1.toByte()
        out[6] = (sps.size shr 8).toByte()
        out[7] = sps.size.toByte()
        sps.copyInto(out, 8)
        var i = 8 + sps.size
        out[i++] = 1
        out[i++] = (pps.size shr 8).toByte()
        out[i++] = pps.size.toByte()
        pps.copyInto(out, i)
        return out
    }

    private fun assertRefused(bytes: ByteArray, reason: String) {
        try {
            MirrorStreamServer.parseConfigRecord(bytes)
            fail("the record should have been refused")
        } catch (e: IllegalArgumentException) {
            assertTrue("unexpected reason: ${e.message}", e.message.orEmpty().contains(reason))
        }
    }
}
