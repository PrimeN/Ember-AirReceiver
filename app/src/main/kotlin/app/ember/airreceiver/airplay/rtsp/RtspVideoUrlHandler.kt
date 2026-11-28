// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.rtsp

import app.ember.airreceiver.airplay.pairing.PlistCodec
import app.ember.airreceiver.airplay.playback.MediaStartPosition
import app.ember.airreceiver.airplay.playback.PlaybackPhase
import app.ember.airreceiver.airplay.playback.PlaybackInfo
import app.ember.airreceiver.util.FetchableUrlPolicy
import app.ember.airreceiver.util.Logger

/**
 * RtspVideoUrlHandler — handles the AirPlay video URL mode (playing a media file, not mirroring).
 *
 * WHAT: Answers `POST /play`, `POST /rate`, `POST /scrub`, `POST /stop` and `GET /playback-info`
 * — the endpoints an Apple TV uses to play a URL and to drive its transport controls.
 *
 * WHY: URL mode is a completely different feature from screen mirroring, but it travels over the
 * same RTSP socket. Giving it its own handler keeps the mirroring code free of URL/transport
 * details and makes the transport state easy to follow.
 *
 * Example:
 *   val video = RtspVideoUrlHandler(onVideoPlay = { url, start -> }, ...)
 *   val response = video.handleVideoPlay(request)   // 200 OK, the player starts the URL
 */
internal class RtspVideoUrlHandler(
    private val onVideoPlay: (url: String, startPosition: MediaStartPosition) -> Unit,
    private val onVideoRate: (rate: Float) -> Unit,
    private val onVideoScrub: (positionSec: Double) -> Unit,
    private val onVideoStop: () -> Unit,
    private val onPlaybackInfo: () -> app.ember.airreceiver.airplay.playback.PlaybackInfo?,
    private val admitPlayback: () -> Boolean = { true },
    private val onSenderPlaylist: (RtspRequest, String, MediaStartPosition) -> Int? = { _, _, _ -> null }
) {

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    internal fun handleVideoPlay(request: RtspRequest): RtspResponse {
        val (url, start) = parsePlayBody(request)
        if (url.isNullOrBlank()) {
            Logger.w("POST /play with no Content-Location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        // The television fetches the URL itself. A URL that must not be fetched — a
        // file:// path, a local address, the cloud metadata service of the router — would
        // turn the receiver into a request proxy or a file reader for the home network.
        // The URL itself is not logged: it can carry credentials.
        if (!FetchableUrlPolicy.isFetchable(url)) {
            Logger.w("POST /play rejected: the media URL is not fetchable")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        if (!admitPlayback()) return RtspResponse(503, "Receiver Busy", protocol = request.responseProtocol())
        Logger.i("Accepted AirPlay URL playback request")
        val relayStatus = onSenderPlaylist(request, url, start)
        if (relayStatus == null) onVideoPlay(url, start)
        if (relayStatus != null && relayStatus != 200) {
            return RtspResponse(relayStatus, "Playlist Unavailable", protocol = request.responseProtocol())
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Extracts the media URL + start fraction from a /play body (plist `Content-Location` or text). */
    private fun parsePlayBody(request: RtspRequest): Pair<String?, MediaStartPosition> {
        val fields: Map<String, Any?> = if (request.isPlistBody()) {
            runCatching { PlistCodec.decode(request.bodyBytes) }.getOrDefault(emptyMap())
        } else {
            request.body.lineSequence().filter { ':' in it }.associate {
                it.substringBefore(':').trim() to it.substringAfter(':').trim()
            }
        }
        fun value(name: String): Any? = fields.entries.firstOrNull { it.key.equals(name, true) }?.value
        fun number(name: String): Double? = when (val raw = value(name)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() && it >= 0 }
        return (value("Content-Location") as? String) to MediaStartPosition(
            fraction = number("Start-Position") ?: 0.0,
            seconds = number("Start-Position-Seconds")
        )
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    internal fun handleVideoRate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    internal fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    internal fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(java.util.Locale.ROOT, info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /** POST /stop — stop URL playback. */
    internal fun handleVideoStop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (video URL)")
        onVideoStop()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    internal fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            when (info?.phase) {
                PlaybackPhase.FAILED -> mapOf("readyToPlay" to false, "errorCode" to -1L)
                PlaybackPhase.ENDED -> mapOf("readyToPlay" to false, "duration" to -1.0)
                else -> mapOf("readyToPlay" to false, "playbackBufferEmpty" to true)
            }
        } else {
            val buffering = info.phase == PlaybackPhase.BUFFERING
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                "playbackBufferEmpty" to buffering,
                "playbackBufferFull" to (info.bufferedUntilSec?.let { it >= info.durationSec } == true),
                "playbackLikelyToKeepUp" to (!buffering && info.rate > 0),
                "loadedTimeRanges" to listOfNotNull(info.bufferedUntilSec?.let { mapOf("start" to 0.0, "duration" to it) }),
                "seekableTimeRanges" to if (info.seekable) listOf(mapOf("start" to 0.0, "duration" to info.durationSec)) else emptyList(),
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
}
