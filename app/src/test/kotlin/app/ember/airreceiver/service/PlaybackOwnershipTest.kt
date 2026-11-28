package app.ember.airreceiver.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOwnershipTest {
    @Test
    fun `keep current rejects another receiver without disconnecting playback`() {
        val ownership = PlaybackOwnership()
        val current = Any()
        var disconnects = 0
        assertTrue(ownership.acquire(current, false) { disconnects++ })
        assertFalse(ownership.acquire(Any(), false) {})
        assertEquals(0, disconnects)
        assertTrue(ownership.acquire(current, false) {})
    }

    @Test
    fun `authorized takeover waits for release and rejects concurrent requests`() {
        val ownership = PlaybackOwnership()
        val previous = Any()
        val replacement = Any()
        assertTrue(ownership.acquire(previous, false) {
            assertFalse(ownership.acquire(Any(), true) {})
            ownership.release(previous)
        })

        assertTrue(ownership.acquire(replacement, true) {})
        ownership.release(previous)
        assertFalse(ownership.acquire(Any(), false) {})
        ownership.release(replacement)
        assertTrue(ownership.acquire(Any(), false) {})
    }
}
