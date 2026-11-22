// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamSupportTest — pins the "what can this receiver play, and what do we tell the user"
 * decision that [StreamSupport] owns.
 *
 * WHY: the RTSP layer and the UI both rely on this decision; if they disagreed, a session could
 * be shown as playing while nothing renders, or a notice could appear during a working mirror.
 *
 * Example: `StreamSupport.noticeFor(setOf(110, 96))` is null (a normal mirror session).
 */
class StreamSupportTest {

    @Test
    fun `mirror video and realtime audio are renderable`() {
        assertTrue(StreamSupport.canRender(StreamSupport.TYPE_MIRROR_VIDEO))
        assertTrue(StreamSupport.canRender(StreamSupport.TYPE_REALTIME_AUDIO))
    }

    @Test
    fun `buffered audio is not renderable yet`() {
        assertFalse(StreamSupport.canRender(StreamSupport.TYPE_BUFFERED_AUDIO))
    }

    @Test
    fun `an unknown stream type is not renderable`() {
        assertFalse(StreamSupport.canRender(999))
    }

    @Test
    fun `buffered audio alone yields the buffered-audio notice`() {
        assertEquals(
            StreamSupport.Notice.BUFFERED_AUDIO_UNSUPPORTED,
            StreamSupport.noticeFor(StreamSupport.TYPE_BUFFERED_AUDIO)
        )
    }

    @Test
    fun `a renderable type yields no notice`() {
        assertNull(StreamSupport.noticeFor(StreamSupport.TYPE_MIRROR_VIDEO))
        assertNull(StreamSupport.noticeFor(StreamSupport.TYPE_REALTIME_AUDIO))
    }

    @Test
    fun `an unknown type yields no notice (ignored, not announced)`() {
        assertNull(StreamSupport.noticeFor(42))
    }

    @Test
    fun `a session with any renderable stream shows no notice`() {
        // mirror video + its realtime audio — plays normally.
        assertNull(StreamSupport.noticeFor(setOf(110, 96)))
        // mirror video accepted alongside a buffered stream — the video still plays, so no notice.
        assertNull(StreamSupport.noticeFor(setOf(110, 103)))
    }

    @Test
    fun `a session with nothing renderable surfaces the notice`() {
        assertEquals(
            StreamSupport.Notice.BUFFERED_AUDIO_UNSUPPORTED,
            StreamSupport.noticeFor(setOf(StreamSupport.TYPE_BUFFERED_AUDIO))
        )
    }

    @Test
    fun `an empty session shows no notice`() {
        assertNull(StreamSupport.noticeFor(emptySet()))
    }
}
