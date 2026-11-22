package app.ember.airreceiver.airplay.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayoutTest {
    @Test
    fun waitsInShortStepsUntilTheOutputApproachesTheDeadline() {
        val now = 1_000_000_000L
        val deadline = now + 120_000_000L
        assertEquals(5_000_000L, AudioPlayout.waitNanos(deadline, now, 20_000_000L))
        assertEquals(5_000_000L, AudioPlayout.waitNanos(deadline, now + 60_000_000L, 20_000_000L))
        assertEquals(0L, AudioPlayout.waitNanos(deadline, now + 99_000_000L, 20_000_000L))
    }

    @Test
    fun dropsLateFramesButLetsUnclockedAndDiscontinuousStreamsPlay() {
        val now = 1_000_000_000L
        assertEquals(AudioPlayout.DROP_FRAME, AudioPlayout.waitNanos(now - 90_000_000L, now, 20_000_000L))
        assertEquals(0L, AudioPlayout.waitNanos(now - 800_000_000L, now, 20_000_000L))
        assertEquals(0L, AudioPlayout.waitNanos(0L, now, 20_000_000L))
    }

    @Test
    fun subtractsTimeElapsedSinceTheHardwareTimestamp() {
        val now = 1_000_000_000L
        assertEquals(30_000_000L, AudioPlayout.queuedNanos(4_800L, 48_000, now, 2_400L, now - 20_000_000L, 0))
    }

    @Test
    fun fallsBackToPlaybackHeadAcrossCounterRollover() {
        val frames = (1L shl 32) + 480
        assertEquals(20_000_000L, AudioPlayout.queuedNanos(frames, 48_000, 800_000_000L, 0L, 1L, -480))
    }
}
