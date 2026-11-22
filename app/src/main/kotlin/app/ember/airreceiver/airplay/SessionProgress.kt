// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay

/**
 * SessionProgress — records how far one AirPlay session got, so a failure says *where* it stopped.
 *
 * WHY: without this, a session that does not play jumps from ADVERTISING straight to ERROR, and the
 * log gives no way to tell apart "pairing failed", "the FairPlay key exchange failed", "the stream
 * was set up but no media arrived" and "media started then died". Those have completely different
 * causes. Recording the ordered milestones with their elapsed time turns a black screen into a
 * timeline a developer (or a device tester following DEVICE_TEST_PLAN.md) can read.
 *
 * TRANSPORT, NOT CONTENT. Reaching [Milestone.FAIRPLAY_KEY_READY] or [Milestone.MEDIA_STARTED] means
 * the AirPlay *transport* handshake and session encryption worked — the pairing and the per-session
 * key exchange. It says nothing about whether the *content* can be shown: a FairPlay-Streaming
 * protected source (e.g. a protected video app) can complete the whole transport handshake and still
 * render nothing. [summary] keeps these two ideas apart on purpose, so a healthy handshake is never
 * mistaken for "protected playback works".
 *
 * Thread-safety: milestones are marked from the RTSP thread and the media coroutines, so every
 * mutation is synchronized. Marking is idempotent and ordered — a milestone only ever records the
 * first time it is reached, and an earlier milestone reached late never overwrites a later one.
 *
 * Example:
 *   val p = SessionProgress()
 *   p.begin(); p.mark(Milestone.PAIR_VERIFIED); p.mark(Milestone.FAIRPLAY_KEY_READY)
 *   p.fail("no media pipeline")       // → summary() == "reached FAIRPLAY_KEY_READY, then failed: …"
 */
class SessionProgress(private val clock: () -> Long = System::currentTimeMillis) {

    /** The ordered milestones of an AirPlay session, earliest first. */
    enum class Milestone {
        /** A sender started talking to us (first SETUP or the legacy RECORD). */
        SENDER_CONNECTED,

        /** pair-verify completed — the ECDH shared secret is established. */
        PAIR_VERIFIED,

        /** The FairPlay per-session AES key was decrypted (transport key, not content DRM). */
        FAIRPLAY_KEY_READY,

        /** A mirror video stream (type 110) was set up. */
        VIDEO_STREAM_SETUP,

        /** A realtime audio stream (type 96) was set up. */
        AUDIO_STREAM_SETUP,

        /** A buffered audio-only stream (type 103) was set up. */
        BUFFERED_STREAM_SETUP,

        /** The media pipeline started and the session went CONNECTED. */
        MEDIA_STARTED,
    }

    private val lock = Any()
    private var startMs = 0L
    private val reachedAt = LinkedHashMap<Milestone, Long>()
    private var failure: String? = null
    private var failedAtMs = 0L

    /** Starts a new session timeline, clearing any previous one. */
    fun begin() = synchronized(lock) {
        startMs = clock()
        reachedAt.clear()
        failure = null
        failedAtMs = 0L
    }

    /**
     * Records that [milestone] was reached, if it has not been already. The first session activity
     * also implies [Milestone.SENDER_CONNECTED], so marking any milestone begins the timeline when
     * it has not been begun yet.
     */
    fun mark(milestone: Milestone) = synchronized(lock) {
        if (startMs == 0L) startMs = clock()
        reachedAt.getOrPut(milestone) { clock() - startMs }
        Unit
    }

    /** Records a terminal failure with a short [reason]; the first failure wins. */
    fun fail(reason: String) = synchronized(lock) {
        if (failure == null) {
            failure = reason
            failedAtMs = if (startMs == 0L) 0L else clock() - startMs
        }
    }

    /** The furthest milestone reached, or null if the session never started. */
    fun furthest(): Milestone? = synchronized(lock) {
        Milestone.entries.lastOrNull { it in reachedAt }
    }

    /** True once a stream of any kind was set up — the transport reached the media stage. */
    fun reachedAnyStream(): Boolean = synchronized(lock) {
        Milestone.VIDEO_STREAM_SETUP in reachedAt ||
            Milestone.AUDIO_STREAM_SETUP in reachedAt ||
            Milestone.BUFFERED_STREAM_SETUP in reachedAt
    }

    /**
     * A one-line summary for the log and the diagnostics report.
     *
     * It states the furthest transport milestone and, separately, whether media actually started —
     * so "the handshake worked" is never read as "the content played".
     */
    fun summary(): String = synchronized(lock) {
        val far = Milestone.entries.lastOrNull { it in reachedAt }
        val f = failure
        if (far == null) {
            return if (f != null) "failed at ${failedAtMs}ms before any milestone: $f"
            else "no AirPlay session yet"
        }
        val base = "reached $far"
        return when {
            f != null -> "$base, then failed at ${failedAtMs}ms: $f"
            Milestone.MEDIA_STARTED in reachedAt -> "$base — media started (transport OK; " +
                "says nothing about protected content)"
            else -> "$base — no media (transport reached $far but the session stopped before media)"
        }
    }

    /** A multi-line timeline (one milestone per line with its elapsed time) for a verbose log dump. */
    fun timeline(): String = synchronized(lock) {
        if (reachedAt.isEmpty() && failure == null) return "AirPlay session: not started"
        val lines = reachedAt.entries.map { (m, ms) -> "  +${ms}ms  $m" }.toMutableList()
        failure?.let { lines.add("  +${failedAtMs}ms  FAILED: $it") }
        return "AirPlay session timeline:\n" + lines.joinToString("\n")
    }
}
