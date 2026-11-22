// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay

/**
 * StreamSupport — the single source of truth for which AirPlay stream types this receiver can
 * actually render, and the user-facing notice to show when a sender starts one it cannot.
 *
 * WHY: a sender can SETUP several stream types in one session (110 mirror video, 96 realtime
 * audio, 103 buffered audio). The receiver accepts the TCP/UDP connection for each so the sender
 * does not abort the whole session, but it cannot play every type. Before this helper, an
 * unplayable type (buffered audio) was accepted and then silently dropped, so the user saw a
 * connected session with no picture and no sound and no reason why. Keeping the "can we render
 * this?" decision in one tested place means the RTSP layer and the UI always agree on the answer,
 * and the user is told what is happening instead of staring at a black screen.
 *
 * This is NOT about content DRM. The buffered-audio transport is encrypted with the AirPlay
 * session keys the receiver already establishes during pairing; "not yet supported" here means
 * the playback path is unimplemented, not that the content is protected. FairPlay Streaming DRM
 * for protected video is a separate matter and out of scope for this receiver.
 *
 * Example:
 *   if (!StreamSupport.canRender(StreamSupport.TYPE_BUFFERED_AUDIO)) {
 *       nowPlaying.npNotice = StreamSupport.noticeFor(StreamSupport.TYPE_BUFFERED_AUDIO)
 *   }
 */
object StreamSupport {

    /** AirPlay 2 mirror video stream (H.264 over the interleaved RTP data connection). */
    const val TYPE_MIRROR_VIDEO = 110

    /** AirPlay 2 realtime audio stream (mirroring companion audio: AAC-ELD/AAC-LC over UDP). */
    const val TYPE_REALTIME_AUDIO = 96

    /** AirPlay 2 buffered audio-only stream (Audio-only AirPlay senders → TV, over TCP). */
    const val TYPE_BUFFERED_AUDIO = 103

    /**
     * A user-facing reason a session is connected but not playing. The UI maps each constant to a
     * localized string; the enum keeps the decision in the protocol layer free of Android strings.
     */
    enum class Notice {
        /** A buffered audio-only source (type 103) connected, but its playback path is not built yet. */
        BUFFERED_AUDIO_UNSUPPORTED
    }

    /** The stream types this receiver can actually play. */
    private val RENDERABLE = setOf(TYPE_MIRROR_VIDEO, TYPE_REALTIME_AUDIO)

    /** True when [streamType] has a working playback path in this receiver. */
    fun canRender(streamType: Int): Boolean = streamType in RENDERABLE

    /**
     * The notice to show for a single accepted-but-unplayable [streamType], or null when the type
     * can be rendered (or is unknown — an unknown type is ignored, not announced).
     */
    fun noticeFor(streamType: Int): Notice? = when {
        canRender(streamType) -> null
        streamType == TYPE_BUFFERED_AUDIO -> Notice.BUFFERED_AUDIO_UNSUPPORTED
        else -> null
    }

    /**
     * The notice to show for a whole session, given every stream type the sender set up.
     *
     * A session that has at least one renderable stream (e.g. mirror video + its audio) plays
     * normally, so no notice is shown even if an extra type was accepted. A notice appears only
     * when nothing in the session can be played — the case where the user would otherwise see a
     * blank, silent "connected" screen.
     */
    fun noticeFor(activeStreamTypes: Set<Int>): Notice? {
        if (activeStreamTypes.any(::canRender)) return null
        return activeStreamTypes.firstNotNullOfOrNull(::noticeFor)
    }
}
