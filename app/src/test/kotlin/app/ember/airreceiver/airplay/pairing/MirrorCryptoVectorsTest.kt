// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MirrorCryptoVectorsTest — known-answer tests for the AirPlay *transport* key derivation.
 *
 * WHY: [MirrorCrypto] derives the keys that decrypt an ordinary mirroring session — the audio key
 * (`SHA-512(aesKey‖ecdh)[:16]`) and the per-stream AES-128-CTR video key/IV. A silent change to any
 * of these — a wrong hash input, a wrong truncation, a wrong label — would break every mirror
 * session with no compile error. These vectors pin the exact transform so a regression is caught
 * in CI instead of on a television.
 *
 * The expected values were produced by an INDEPENDENT reference (Python `hashlib` for SHA-512 and
 * the OpenSSL CLI for AES-128-CTR), not by this code, so the test checks the implementation against
 * the documented algorithm rather than against itself.
 *
 * This is transport-session crypto (the keys every AirPlay session negotiates), not content DRM.
 */
class MirrorCryptoVectorsTest {

    private val aesKey = hex("000102030405060708090a0b0c0d0e0f")
    private val ecdh = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
    private val streamId = 1234567890123456789L

    @Test
    fun `audioKey is SHA-512 of aesKey concat ecdh truncated to 16 bytes`() {
        // Independently computed: hashlib.sha512(aesKey+ecdh).digest()[:16]
        assertArrayEquals(hex("845e82df15aa7c75fa1964c1557f80a8"), MirrorCrypto.audioKey(aesKey, ecdh))
    }

    @Test
    fun `audioKey with an empty ecdh secret still matches the reference`() {
        assertArrayEquals(hex("daa295beed4e2ee94c24015b56af626b"), MirrorCrypto.audioKey(aesKey, ByteArray(0)))
    }

    @Test
    fun `audioKey is exactly 16 bytes`() {
        assertEquals(16, MirrorCrypto.audioKey(aesKey, ecdh).size)
    }

    @Test
    fun `streamCipher decrypts a reference AES-128-CTR ciphertext back to its plaintext`() {
        // plaintext 00..2f (48 bytes), encrypted by OpenSSL AES-128-CTR with the key and IV that
        // MirrorCrypto derives for (aesKey, ecdh, streamId). If the key/IV derivation drifts, the
        // decrypt no longer reproduces the plaintext.
        val plaintext = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b" +
            "1c1d1e1f202122232425262728292a2b2c2d2e2f")
        val ciphertext = hex("b8e22fc18cd34040476b243214ceaffce6faf2a860d24ca18bebd655" +
            "70b4b053d4cc168b031f1d47bd0ef4a7cba1e94d")
        val decrypted = MirrorCrypto.streamCipher(aesKey, ecdh, streamId).update(ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `streamCipher is a continuous keystream across sequential update calls`() {
        // The real stream calls update() once per payload; splitting the same ciphertext across two
        // update() calls must yield the same plaintext as one call (keystream stays continuous).
        val ciphertext = hex("b8e22fc18cd34040476b243214ceaffce6faf2a860d24ca18bebd655" +
            "70b4b053d4cc168b031f1d47bd0ef4a7cba1e94d")
        val cipher = MirrorCrypto.streamCipher(aesKey, ecdh, streamId)
        val first = cipher.update(ciphertext.copyOfRange(0, 16))
        val rest = cipher.update(ciphertext.copyOfRange(16, ciphertext.size))
        val combined = first + rest
        val oneShot = MirrorCrypto.streamCipher(aesKey, ecdh, streamId).update(ciphertext)
        assertArrayEquals(oneShot, combined)
    }

    @Test
    fun `avccToAnnexB replaces 4-byte length prefixes with start codes`() {
        // Two NAL units: length 2 (aa bb) and length 3 (cc dd ee).
        val avcc = hex("00000002aabb00000003ccddee")
        val expected = hex("00000001aabb00000001ccddee")
        assertArrayEquals(expected, MirrorCrypto.avccToAnnexB(avcc))
    }

    @Test
    fun `avccToAnnexB stops at a length that runs past the buffer`() {
        // A valid NAL (len 1: aa) then a length (0x7fffffff) that exceeds the remaining bytes.
        val avcc = hex("00000001aa7fffffffbb")
        val expected = hex("00000001aa")
        assertArrayEquals(expected, MirrorCrypto.avccToAnnexB(avcc))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
}
