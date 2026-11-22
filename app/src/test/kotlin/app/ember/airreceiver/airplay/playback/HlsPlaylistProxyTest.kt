package app.ember.airreceiver.airplay.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistProxyTest {
    @Test
    fun resolvesRelativeVariantsAndInitSegments() {
        val source = "https://example.org/live/root.m3u8"
        val resources = mutableListOf<Pair<String, Boolean>>()
        val data = (
            "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/track\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2000,AUDIO=\"audio\"\n" +
                "stream/variant\n" +
                "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "part-1.m4s\n"
            ).toByteArray()
        val output = HlsPlaylistProxy.rewrite(data, source) { url, playlist ->
            resources.add(url to playlist)
            "/media/${resources.size}"
        }.decodeToString()

        assertEquals(listOf(
            "https://example.org/live/audio/track" to true,
            "https://example.org/live/stream/variant" to true,
            "https://example.org/live/init.mp4" to false,
            "https://example.org/live/part-1.m4s" to false,
        ), resources)
        assertTrue(output.contains("URI=\"/media/3\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEncryptedMedia() {
        val data = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"".toByteArray()
        HlsPlaylistProxy.rewrite(data, "https://example.org/master.m3u8") { _, _ -> error("unexpected URL") }
    }
}
