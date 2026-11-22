package app.ember.airreceiver.airplay.audio

/** Paces PCM against the same monotonic deadlines used by the mirror renderer. */
internal object AudioPlayout {
    const val DROP_FRAME = -1L
    private const val MAX_FUTURE_NS = 250_000_000L
    private const val MAX_PAST_NS = 500_000_000L
    private const val MAX_LATE_NS = 100_000_000L
    private const val WRITE_LEAD_NS = 2_000_000L
    private const val MAX_WAIT_STEP_NS = 5_000_000L
    private const val MAX_TIMESTAMP_AGE_NS = 500_000_000L
    private const val FRAME_COUNTER_PERIOD = 1L shl 32

    /**
     * Returns the signed delay before writing the next PCM frame. An unavailable or stale
     * sender clock falls back to arrival order. Plausibly timed frames are dropped when late.
     */
    fun waitNanos(ptsNanos: Long, now: Long, queuedNanos: Long): Long {
        if (ptsNanos == 0L) return 0L
        val remaining = ptsNanos - now
        if (remaining !in -MAX_PAST_NS..MAX_FUTURE_NS) return 0L
        val lead = remaining - queuedNanos
        if (lead < -MAX_LATE_NS) return DROP_FRAME
        return (lead - WRITE_LEAD_NS).coerceIn(0L, MAX_WAIT_STEP_NS)
    }

    /** Predict when the next PCM frame would play, falling back to the 32-bit playback head. */
    fun queuedNanos(
        framesWritten: Long,
        sampleRate: Int,
        now: Long,
        timestampFrame: Long?,
        timestampNanos: Long?,
        playbackHead: Int
    ): Long {
        if (framesWritten <= 0 || sampleRate <= 0) return 0L
        if (timestampFrame != null && timestampNanos != null &&
            timestampFrame in 0L..framesWritten && now - timestampNanos in 0L..MAX_TIMESTAMP_AGE_NS) {
            val queue = (framesWritten - timestampFrame).coerceAtMost(sampleRate.toLong())
            return (queue * 1_000_000_000L / sampleRate - (now - timestampNanos)).coerceIn(0L, MAX_FUTURE_NS)
        }
        val unsignedHead = playbackHead.toLong() and (FRAME_COUNTER_PERIOD - 1)
        val headInWriteEpoch = (framesWritten and -FRAME_COUNTER_PERIOD) + unsignedHead
        val played = if (headInWriteEpoch > framesWritten) headInWriteEpoch - FRAME_COUNTER_PERIOD else headInWriteEpoch
        if (played < 0) return 0L
        val queued = (framesWritten - played).coerceAtMost(sampleRate.toLong())
        return (queued * 1_000_000_000L / sampleRate).coerceAtMost(MAX_FUTURE_NS)
    }
}
