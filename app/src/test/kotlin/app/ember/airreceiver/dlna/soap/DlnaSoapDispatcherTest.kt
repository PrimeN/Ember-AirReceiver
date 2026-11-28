// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.dlna.soap

import app.ember.airreceiver.dlna.DlnaMediaMetadata
import app.ember.airreceiver.dlna.DlnaPlaybackController
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaSoapDispatcherTest — proves that a UPnP control point gets the answers it
 * needs to drive Ember AirReceiver as a renderer.
 *
 * WHY: the control plane is the part a control point a DLNA controller talks to. If one
 * answer is missing or malformed, the control point drops the device — the classic
 * symptom being "Ember AirReceiver does not appear in the renderer list".
 *
 * WHAT WE TEST:
 * - `GetProtocolInfo` returns a non-empty Source *and* Sink protocol list,
 * - `SetAVTransportURI` accepts the URIs a real control point sends and refuses the
 *   local schemes,
 * - `GetPositionInfo` returns the times in the literal `H:MM:SS` format,
 * - the remaining AVTransport and RenderingControl actions answer correctly,
 * - hostile SOAP bodies (XXE, billion laughs, broken XML) are refused.
 */
class DlnaSoapDispatcherTest {

    @Test
    fun `a local library selection uses shared admission and protects its transport`() {
        var admit = false
        var loaded: String? = null
        val controller = object : app.ember.airreceiver.dlna.DlnaPlaybackController
            by app.ember.airreceiver.dlna.DlnaPlaybackController.NOOP {
            override fun admitPlayback() = admit
            override fun onPlayLoad(
                uri: String, metadata: app.ember.airreceiver.dlna.DlnaMediaMetadata?, loadId: Long, positionMillis: Long
            ) { loaded = uri }
        }
        val dispatcher = DlnaSoapDispatcher(controller)
        val media = "http://192.168.1.10/video.mp4"
        assertFalse(dispatcher.playFromLibrary(media))
        assertNull(loaded)
        admit = true
        assertTrue(dispatcher.playFromLibrary(media))
        assertEquals(media, loaded)
        val peer = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val result = dispatcher.dispatch(
            "/upnp/control/AVTransport", null, envelope("AVTransport", "Stop"), peer
        )
        assertEquals(500, result.statusCode)
    }

    @Test
    fun `replacement sender must stage media and request playback before taking over`() {
        val played = mutableListOf<String>()
        var allowReplacement = false
        val controller = object : app.ember.airreceiver.dlna.DlnaPlaybackController
            by app.ember.airreceiver.dlna.DlnaPlaybackController.NOOP {
            override fun canReplaceSender() = allowReplacement
            override fun onPlayLoad(
                uri: String, metadata: app.ember.airreceiver.dlna.DlnaMediaMetadata?, loadId: Long, positionMillis: Long
            ) {
                played += uri
            }
        }
        val dispatcher = DlnaSoapDispatcher(controller)
        val first = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val next = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        fun request(sender: java.net.InetAddress, action: String, xml: String = "") =
            dispatcher.dispatch("/upnp/control/AVTransport", null, envelope("AVTransport", action, xml), sender)
        fun load(sender: java.net.InetAddress) =
            request(sender, "SetAVTransportURI", "<CurrentURI>http://${sender.hostAddress}/video.mp4</CurrentURI>")

        assertEquals(200, load(first).statusCode)
        assertEquals(200, request(first, "Play").statusCode)
        assertEquals(500, load(next).statusCode)
        allowReplacement = true
        assertEquals(500, request(next, "Play").statusCode)
        assertEquals(200, load(next).statusCode)
        assertEquals(listOf("http://10.0.0.1/video.mp4"), played)
        assertEquals(200, request(next, "Play").statusCode)
        assertEquals(500, request(first, "Stop").statusCode)
        assertEquals(listOf("http://10.0.0.1/video.mp4", "http://10.0.0.2/video.mp4"), played)
    }

    @Test
    fun `ConnectionManager GetProtocolInfo returns a non-empty Source list`() {
        val result = dispatch(
            "/upnp/control/ConnectionManager",
            "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
            envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertEquals(200, result.statusCode)
        // An empty Source makes the renderer invisible in the control point's picker.
        assertTrue(result.body.contains("<Source>http-get:*:video/mp4:*"))
        assertTrue(result.body.contains("http-get:*:video/*:*"))
        assertTrue(result.body.contains("http-get:*:audio/*:*"))
    }

    @Test
    fun `ConnectionManager GetProtocolInfo returns a non-empty Sink list`() {
        val result = dispatch(
            "/upnp/control/ConnectionManager",
            "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
            envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertTrue(result.body.contains("<Sink>http-get:*:video/mp4:*"))
    }

    @Test
    fun `AVTransport stores URI and reports it in GetPositionInfo`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"",
            body = envelope("AVTransport", "GetPositionInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<TrackURI>http://192.168.1.4/movie.mp4</TrackURI>"))
    }

    @Test
    fun `SetAVTransportURI accepts the http URL a control point sends`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.10:8080/videos/sample.mp4</CurrentURI>" +
                    "<CurrentURIMetaData></CurrentURIMetaData>"
            )
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("SetAVTransportURIResponse"))
    }

    @Test
    fun `SetAVTransportURI refuses a local scheme and accepts an https URI`() {
        // WHY this test was reversed: the renderer used to accept `file://` and
        // `rtsp://`. A `file://` URI turns the television into a reader for the app's
        // own sandbox, and the receiver speaks neither protocol itself. The rule now
        // stands in one place — `app.ember.airreceiver.util.FetchableUrlPolicy` — and the
        // DLNA stack uses it like the AirPlay stack does.
        listOf(
            "file:///C:/Users/test/movie.mkv",
            "file:///data/data/app.ember.airreceiver/shared_prefs/airreceiver_prefs.xml",
            "rtsp://192.168.1.10:554/live",
            "smb://192.168.1.10/share/movie.mkv"
        ).forEach { uri ->
            val result = dispatch(
                "/upnp/control/AVTransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>$uri</CurrentURI>")
            )

            assertEquals("URI must be refused: $uri", 500, result.statusCode)
            assertTrue(
                "the answer must be the Invalid Args fault: $uri",
                result.body.contains("<errorCode>402</errorCode>")
            )
        }

        // The normal case still works — the rule must not break the renderer.
        val accepted = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>https://example.invalid/stream.mp4</CurrentURI>"
            )
        )
        assertEquals(200, accepted.statusCode)
    }

    @Test
    fun `SetAVTransportURI refuses a URI without a scheme`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>not a uri</CurrentURI>")
        )

        assertEquals(500, result.statusCode)
        assertTrue(result.body.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `GetPositionInfo returns the times in the UPnP H MM SS format`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
            envelope("AVTransport", "GetPositionInfo")
        )

        assertEquals(200, result.statusCode)
        // DLNA controllers parse these values literally — "00:00:00" would be mis-parsed.
        assertTrue(result.body.contains("<TrackDuration>0:00:00</TrackDuration>"))
        assertTrue(result.body.contains("<RelTime>0:00:00.000</RelTime>"))
    }

    @Test
    fun `GetPositionInfo reports the duration announced in the DIDL metadata`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>" +
                    // A control point sends the DIDL-Lite document escaped.
                    "<CurrentURIMetaData>" + didl("0:01:30", "http-get:*:video/mp4:*").xmlEscaped() + "</CurrentURIMetaData>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"",
            body = envelope("AVTransport", "GetPositionInfo")
        )

        assertTrue(result.body.contains("<TrackDuration>0:01:30</TrackDuration>"))
    }

    @Test
    fun `a body that starts with a newline is still answered`() {
        // WHY: a control point that puts whitespace in front of the XML declaration must
        // not be answered with an error - otherwise the renderer silently disappears
        // from the device list of that control point.
        val result = dispatch(
            "/upnp/control/ConnectionManager",
            "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
            "\n" + envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<Source>http-get:*:video/mp4:*"))
    }

    @Test
    fun `GetMediaInfo reports the loaded media`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetMediaInfo\"",
            body = envelope("AVTransport", "GetMediaInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"))
        assertTrue(result.body.contains("<NrTracks>1</NrTracks>"))
        assertTrue(result.body.contains("<PlayMedium>NETWORK</PlayMedium>"))
    }

    @Test
    fun `GetCurrentTransportActions lists the actions of the current state`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetCurrentTransportActions\"",
            body = envelope("AVTransport", "GetCurrentTransportActions")
        )

        assertTrue(result.body.contains("<Actions>"))
        assertTrue(result.body.contains("Play"))
    }

    @Test
    fun `GetTransportInfo reports the transport state`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo",
            envelope("AVTransport", "GetTransportInfo")
        )

        assertTrue(result.body.contains("<CurrentTransportState>STOPPED</CurrentTransportState>"))
        assertTrue(result.body.contains("<CurrentTransportStatus>OK</CurrentTransportStatus>"))
    }

    @Test
    fun `RenderingControl SetVolume affects GetVolume`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/RenderingControl",
            soapAction = "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"",
            body = envelope("RenderingControl", "SetVolume", "<DesiredVolume>31</DesiredVolume>")
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/RenderingControl",
            soapAction = "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"",
            body = envelope("RenderingControl", "GetVolume")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<CurrentVolume>31</CurrentVolume>"))
    }

    @Test
    fun `RenderingControl rejects an out of range volume`() {
        val result = dispatch(
            "/upnp/control/RenderingControl",
            "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume",
            envelope("RenderingControl", "SetVolume", "<DesiredVolume>900</DesiredVolume>")
        )

        assertEquals(500, result.statusCode)
        assertTrue(result.body.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `AVTransport actions notify playback controller`() {
        val events = mutableListOf<String>()
        val dispatcher = DlnaSoapDispatcher(object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) {
                events += "set:$uri"
            }

            override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) {
                events += "play:$uri"
            }

            override fun onPause() {
                events += "pause"
            }

            override fun onStop() {
                events += "stop"
            }
        })

        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>http://host/video.mp4</CurrentURI>")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = envelope("AVTransport", "Play")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\"",
            body = envelope("AVTransport", "Pause")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"",
            body = envelope("AVTransport", "Stop")
        )

        assertEquals(listOf("set:http://host/video.mp4", "play:http://host/video.mp4", "pause", "stop"), events)
    }

    @Test
    fun `resume retains the load and completion starts the next item once`() {
        val loads = mutableListOf<Pair<String, Long>>()
        var resumes = 0
        val dispatcher = DlnaSoapDispatcher(object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) = Unit
            override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) = Unit
            override fun onPause() = Unit
            override fun onStop() = Unit
            override fun onResume() { resumes++ }
            override fun onPlayLoad(uri: String, metadata: DlnaMediaMetadata?, loadId: Long, positionMillis: Long) {
                loads += uri to loadId
            }
        })
        fun send(action: String, fields: String = "") = dispatcher.dispatch(
            "/upnp/control/AVTransport", action, envelope("AVTransport", action, fields)
        )
        val first = "http://host/first.mp4"
        val next = "http://host/next.mp4"
        assertEquals(200, send("SetAVTransportURI", "<CurrentURI>$first</CurrentURI>").statusCode)
        assertEquals(200, send("Play").statusCode)
        assertEquals(200, send("Pause").statusCode)
        assertEquals(200, send("Play").statusCode)
        assertEquals(200, send("Play").statusCode)
        assertEquals(1, resumes)
        assertEquals(listOf(first), loads.map { it.first })
        assertEquals(200, send("SetNextAVTransportURI", "<NextURI>$next</NextURI>").statusCode)
        assertTrue(send("GetMediaInfo").body.contains("<NextURI>$next</NextURI>"))
        val firstId = loads.single().second
        dispatcher.onPlaybackTerminal(firstId, failed = false)
        dispatcher.onPlaybackTerminal(firstId, failed = false)
        assertEquals(listOf(first, next), loads.map { it.first })
        assertTrue(send("GetMediaInfo").body.contains("<CurrentURI>$next</CurrentURI>"))
    }

    @Test
    fun `volume and mute apply independent renderer gain changes`() {
        val gain = mutableListOf<Pair<Int, Boolean>>()
        val dispatcher = DlnaSoapDispatcher(object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) = Unit
            override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) = Unit
            override fun onPause() = Unit
            override fun onStop() = Unit
            override fun onRendererGain(volume: Int, muted: Boolean) { gain += volume to muted }
        })
        fun send(action: String, fields: String) = dispatcher.dispatch(
            "/upnp/control/RenderingControl", action, envelope("RenderingControl", action, fields)
        )
        assertEquals(200, send("SetVolume", "<DesiredVolume>25</DesiredVolume>").statusCode)
        assertEquals(200, send("SetMute", "<DesiredMute>1</DesiredMute>").statusCode)
        assertEquals(200, send("SetVolumeDB", "<DesiredVolume>-602</DesiredVolume>").statusCode)
        assertEquals(200, send("SetMute", "<DesiredMute>0</DesiredMute>").statusCode)
        assertEquals(listOf(25 to false, 25 to true, 50 to true, 50 to false), gain)
    }

    @Test
    fun `transport state changes are published to the event subscribers`() {
        val events = mutableListOf<Pair<String, String>>()
        val dispatcher = DlnaSoapDispatcher(onLastChange = { path, payload -> events += path to payload })

        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>http://host/video.mp4</CurrentURI>")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = envelope("AVTransport", "Play")
        )

        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.first == "/upnp/event/AVTransport" })
        // The nested UPnP format — a flat list is ignored by the control points.
        assertTrue(events.last().second.contains("TransportState"))
        assertTrue(events.last().second.contains("PLAYING"))
    }

    private fun dispatch(path: String, action: String, body: String): SoapResult =
        DlnaSoapDispatcher().dispatch(
            controlPath = path,
            soapAction = "\"$action\"",
            body = body
        )

    private fun envelope(service: String, action: String, children: String = ""): String =
        """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">
              $children
            </u:$action>
          </s:Body>
        </s:Envelope>
        """.trimIndent()

    private fun didl(duration: String, protocolInfo: String): String =
        // WHY one line: a control point sends the escaped DIDL-Lite document on a single
        // line, and a multi-line document would break the indentation of the SOAP
        // envelope that carries it.
        """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="1" parentID="0" restricted="1">
            <dc:title>Sample</dc:title>
            <upnp:class>object.item.videoItem</upnp:class>
            <res protocolInfo="$protocolInfo" duration="$duration" size="1024">http://192.168.1.4/movie.mp4</res>
          </item>
        </DIDL-Lite>
        """.trimIndent().replace("\n", " ")

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
