package app.ember.airreceiver.airplay.playback

import java.net.URI
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

    @Test
    fun keepsOriginQueryAndForwardsOnlyValidatedReloadFields() {
        val source = "https://example.org/live/root.m3u8?token=a%2Fb&_HLS_msn=12"
        assertEquals(
            "https://example.org/live/root.m3u8?token=a%2Fb&_HLS_msn=20&_HLS_part=2",
            HlsPlaylistProxy.playlistReloadUri(source, "_HLS_msn=20&_HLS_part=2&_HLS_skip=invalid&token=other")
        )
        assertEquals(source, HlsPlaylistProxy.playlistReloadUri(source, "_HLS_msn=%0A&other=20"))
    }

    @Test(timeout = 5_000)
    fun servesEachSequentialLiveRevisionAndPassesItsReloadCursor() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        HlsPlaylistProxy("https://example.org/live/root.m3u8") { uri ->
            requests.add(uri)
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:${requests.size}\n#EXTINF:4,\nsegment.ts\n".toByteArray()
        }.use { proxy ->
            fun get(query: String): String = URI(proxy.url + query).toURL().openConnection().apply {
                connectTimeout = 1_500
                readTimeout = 1_500
            }.getInputStream().bufferedReader().use { it.readText() }

            assertTrue(get("").contains("#EXT-X-MEDIA-SEQUENCE:1"))
            assertTrue(get("?_HLS_msn=2").contains("#EXT-X-MEDIA-SEQUENCE:2"))
            assertEquals(listOf(
                "https://example.org/live/root.m3u8",
                "https://example.org/live/root.m3u8?_HLS_msn=2"
            ), requests)
        }
    }

}
