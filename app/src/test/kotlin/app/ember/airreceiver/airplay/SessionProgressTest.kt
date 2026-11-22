// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay

import app.ember.airreceiver.airplay.SessionProgress.Milestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionProgressTest — pins the "where did the session stop" tracking.
 *
 * WHY: the value of [SessionProgress] is that a failed session reports the furthest point it
 * reached and keeps "transport handshake succeeded" separate from "media played". Both must be
 * exact, so a diagnostic never implies protected content works just because pairing did.
 *
 * A fake clock makes the elapsed-time output deterministic.
 */
class SessionProgressTest {

    /** A clock the test advances by hand, so elapsed times are exact. */
    private class FakeClock {
        var now = 1_000L
        fun read(): Long = now
    }

    private fun progress(clock: FakeClock) = SessionProgress(clock::read)

    @Test
    fun `a fresh tracker has no session`() {
        val p = SessionProgress()
        assertNull(p.furthest())
        assertFalse(p.reachedAnyStream())
        assertEquals("no AirPlay session yet", p.summary())
    }

    @Test
    fun `furthest returns the latest milestone regardless of mark order`() {
        val p = SessionProgress()
        p.begin()
        p.mark(Milestone.MEDIA_STARTED)
        p.mark(Milestone.PAIR_VERIFIED)   // marked later, but earlier in the order
        assertEquals(Milestone.MEDIA_STARTED, p.furthest())
    }

    @Test
    fun `a milestone records only its first time`() {
        val clock = FakeClock()
        val p = progress(clock)
        p.begin()
        clock.now += 40
        p.mark(Milestone.PAIR_VERIFIED)
        clock.now += 100
        p.mark(Milestone.PAIR_VERIFIED)   // again, later — must not move
        assertTrue(p.timeline().contains("+40ms  PAIR_VERIFIED"))
        assertFalse(p.timeline().contains("+140ms"))
    }

    @Test
    fun `reachedAnyStream reflects stream setup`() {
        val p = SessionProgress()
        p.begin()
        p.mark(Milestone.FAIRPLAY_KEY_READY)
        assertFalse(p.reachedAnyStream())
        p.mark(Milestone.VIDEO_STREAM_SETUP)
        assertTrue(p.reachedAnyStream())
    }

    @Test
    fun `summary keeps a healthy handshake separate from media playing`() {
        val p = SessionProgress()
        p.begin()
        p.mark(Milestone.SENDER_CONNECTED)
        p.mark(Milestone.PAIR_VERIFIED)
        p.mark(Milestone.FAIRPLAY_KEY_READY)
        p.mark(Milestone.VIDEO_STREAM_SETUP)
        // Keys and stream setup done but media never started — must NOT read as success.
        val summary = p.summary()
        assertTrue(summary.contains("VIDEO_STREAM_SETUP"))
        assertTrue(summary.contains("no media"))
        assertFalse(summary.contains("media started"))
    }

    @Test
    fun `summary marks media started as transport-only success`() {
        val p = SessionProgress()
        p.begin()
        p.mark(Milestone.MEDIA_STARTED)
        val summary = p.summary()
        assertTrue(summary.contains("media started"))
        assertTrue(summary.contains("transport OK"))
        assertTrue(summary.contains("protected content"))
    }

    @Test
    fun `a failure records the furthest milestone and the reason`() {
        val clock = FakeClock()
        val p = progress(clock)
        p.begin()
        p.mark(Milestone.FAIRPLAY_KEY_READY)
        clock.now += 250
        p.fail("no media pipeline")
        val summary = p.summary()
        assertTrue(summary.contains("reached FAIRPLAY_KEY_READY"))
        assertTrue(summary.contains("failed at 250ms"))
        assertTrue(summary.contains("no media pipeline"))
    }

    @Test
    fun `only the first failure is kept`() {
        val p = SessionProgress()
        p.begin()
        p.fail("first")
        p.fail("second")
        assertTrue(p.summary().contains("first"))
        assertFalse(p.summary().contains("second"))
    }

    @Test
    fun `begin clears a previous session`() {
        val p = SessionProgress()
        p.begin()
        p.mark(Milestone.MEDIA_STARTED)
        p.fail("boom")
        p.begin()
        assertNull(p.furthest())
        assertEquals("no AirPlay session yet", p.summary())
    }

    @Test
    fun `marking without begin still starts the timeline`() {
        val p = SessionProgress()
        p.mark(Milestone.SENDER_CONNECTED)
        assertEquals(Milestone.SENDER_CONNECTED, p.furthest())
    }
}
