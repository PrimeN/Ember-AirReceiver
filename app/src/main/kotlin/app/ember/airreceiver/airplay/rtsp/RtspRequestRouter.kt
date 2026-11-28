// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.rtsp

import app.ember.airreceiver.util.Logger

/**
 * RtspRequestRouter — dispatches one parsed RTSP request to the handler that answers it.
 *
 * WHAT: Maps the RTSP method — and the URI paths of the AirPlay 2 handshake — to the matching
 * handler: the classic verbs, the pairing endpoints, the mirroring SETUP, the media parameters
 * and the URL-video endpoints.
 *
 * WHY: Every AirPlay sender speaks a slightly different dialect of RTSP (classic verbs, HTTP-style
 * GET/POST, buffered-audio control verbs). Keeping that mapping in one dispatch table makes it
 * readable at a glance instead of spreading it over the socket loop.
 *
 * Example:
 *   val router = RtspRequestRouter(verbs, mirrorHandler, pairingHandler, ...)
 *   val response = router.routeRequest(request)   // 200 OK, or 501 for an unknown method
 */
internal class RtspRequestRouter(
    private val admitPlayback: () -> Boolean = { true },
    private val audioOnlyMode: Boolean = false,
    private val verbs: RtspHandler,
    private val mirrorHandler: RtspMirrorHandler,
    private val pairingHandler: RtspPairingHandler,
    private val parameterHandler: RtspParameterHandler,
    private val videoUrlHandler: RtspVideoUrlHandler,
    private val deviceInfoHandler: RtspDeviceInfoHandler,
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit,
    private val onAudioFlush: (firstSequence: Int?) -> Unit
) {

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        val path = request.uri.substringBefore("?")
        if (!pairingHandler.isAuthorized() && !canHandleBeforeAuthorization(request.method, path)) {
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        if (audioOnlyMode && (request.method == "PUT" || request.method == "DELETE" ||
                (request.method == "POST" && path == "/play"))) {
            return RtspResponse(461, "Unsupported Transport", protocol = request.responseProtocol())
        }
        if ((request.method in setOf("SETUP", "RECORD", "PUT") || (request.method == "POST" && path == "/play")) &&
            !admitPlayback()) {
            return RtspResponse(503, "Receiver Busy", protocol = request.responseProtocol())
        }
        Logger.d("RTSP ${request.method} ${request.uri}")
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        if (pairingHandler.isAuthorized()) {
            request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        }
        return when (request.method) {
            "OPTIONS"       -> verbs.handleOptionsInternal(request)
            "ANNOUNCE"      -> verbs.handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) mirrorHandler.handleMirrorSetup(request) else verbs.handleSetupInternal(request)
            "RECORD"        -> verbs.handleRecordInternal(request)
            "TEARDOWN"      -> verbs.handleTeardownInternal(request)
            "GET_PARAMETER" -> parameterHandler.handleGetParameter(request)
            "SET_PARAMETER" -> parameterHandler.handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> verbs.handlePauseInternal(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> deviceInfoHandler.handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> deviceInfoHandler.handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> deviceInfoHandler.handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> verbs.handlePhotoPutInternal(request)
            "DELETE"        -> verbs.handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> verbs.handleUnknownInternal(request)
        }
    }

    private fun canHandleBeforeAuthorization(method: String, path: String): Boolean = when (method) {
        "OPTIONS" -> true
        "GET" -> path == "/info" || path == "/server-info"
        "POST" -> path in setOf("/pair-setup", "/pair-setup-pin", "/pair-pin-start", "/pair-verify", "/fp-setup", "/feedback")
        else -> false
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/info"          -> deviceInfoHandler.handleInfo(request)
        "/playback-info" -> videoUrlHandler.handlePlaybackInfo(request)
        "/scrub"         -> videoUrlHandler.handleScrubGet(request)
        "/server-info"   -> deviceInfoHandler.handleServerInfo(request)
        else             -> verbs.handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> pairingHandler.handlePairSetup(request)
        "/pair-setup-pin" -> pairingHandler.handleLegacyPairSetupPin(request)   // legacy AirPlay PIN SRP (plist)
        "/pair-pin-start" -> pairingHandler.handlePairPinStart(request)
        "/pair-verify" -> pairingHandler.handlePairVerify(request)
        "/fp-setup"    -> mirrorHandler.handleFpSetup(request)
        "/feedback"    -> deviceInfoHandler.handleFeedback(request)
        "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/play"        -> videoUrlHandler.handleVideoPlay(request)
        "/rate"        -> videoUrlHandler.handleVideoRate(request)
        "/scrub"       -> videoUrlHandler.handleVideoScrubPost(request)
        "/stop"        -> videoUrlHandler.handleVideoStop(request)
        else           -> verbs.handleUnknownInternal(request)
    }

    /** Handles FLUSH — macOS requests we discard buffered media data (seek/pause). */
    private fun handleFlush(request: RtspRequest): RtspResponse {
        val rtpInfo = request.headers.entries.firstOrNull { it.key.equals("RTP-Info", ignoreCase = true) }?.value
        val sequence = rtpInfo?.split(';')?.firstNotNullOfOrNull { field ->
            val parts = field.split('=', limit = 2)
            if (parts.size == 2 && parts[0].trim().equals("seq", ignoreCase = true)) {
                parts[1].trim().toIntOrNull()?.takeIf { it in 0..0xFFFF }
            } else null
        }
        onAudioFlush(sequence)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }
}
