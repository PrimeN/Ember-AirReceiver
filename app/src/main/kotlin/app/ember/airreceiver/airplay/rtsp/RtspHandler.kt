// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.rtsp

import app.ember.airreceiver.airplay.playback.PhotoImageType
import app.ember.airreceiver.airplay.playback.PlaybackInfo
import app.ember.airreceiver.util.Logger
import app.ember.airreceiver.settings.AirPlayPinPolicy
import app.ember.airreceiver.settings.MirrorFormat
import app.ember.airreceiver.util.releaseQuietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * RtspHandler — the public entry point of the AirPlay RTSP control protocol.
 *
 * WHAT: Starts and stops the RTSP control socket and forwards every RTSP verb to the class that
 * implements it — the classic RTSP verbs, the AirPlay 2 pairing, the mirroring SETUP, the media
 * parameters and the URL-video mode.
 *
 * WHY: AirPlay uses RTSP to negotiate codecs, ports and encryption before media flows, and that
 * protocol is large: classic verbs, the AirPlay 2 handshake, mirroring, parameters and URL mode in
 * a single class produced a 1000+ line file. This class stays the one stable API that
 * [app.ember.airreceiver.airplay.AirPlayReceiver] talks to, while the protocol details live in small classes that each own
 * exactly one responsibility.
 *
 * Example:
 *   val handler = RtspHandler(context, onStreamingStarted = { s -> start(s) }, onStreamingStopped = { stop() })
 *   handler.start(scope)                              // listens for senders on port 7000
 *   handler.onVideoNalUnit = { nal, pts -> decode(nal, pts) }
 *   handler.stop()                                    // closes the control socket
 */
open class RtspHandler internal constructor(
    private val context: android.content.Context,
    private val displayWidth: Int = 1920,
    private val displayHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    /**
     * AirPlay 2 mirror SETUP msg 1: supply decrypted AES key + pairing secret + the sender's
     * address and timing port (so the receiver can start NTP). Returns (eventPort, timingPort).
     */
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int> = { _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    /** AirPlay 2 buffered audio-only SETUP (type 103, audio-only senders → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    /** Sender volume change (AirPlay dB: −30…0, or ≤ −144 = mute) via SET_PARAMETER. */
    private val onVolume: (Float) -> Unit = {},
    /** Now-playing track metadata (DMAP) from SET_PARAMETER — any field may be null. */
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit = { _, _, _ -> },
    /** Album artwork (JPEG/PNG bytes) from SET_PARAMETER; empty bytes = artwork cleared. */
    private val onArtwork: (ByteArray) -> Unit = {},
    /** AirPlay video URL mode: POST /play with a media URL + start fraction (0..1). */
    private val onVideoPlay: (url: String, startPosition: app.ember.airreceiver.airplay.playback.MediaStartPosition) -> Unit = { _, _ -> },
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> app.ember.airreceiver.airplay.playback.PlaybackInfo? = { null },
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** When true, require HomeKit-style SRP PIN pairing before streaming (gated by AppSettings). */
    private val advertisedFps: Int = 60,
    private val admitPlayback: () -> Boolean = { true },
    private val audioOnlyMode: Boolean = false,
    private val hevcEnabled: Boolean = false,
    private val pinAuthEnabled: Boolean = false,
    /** Persistent store of paired controllers' Ed25519 keys (for pair-verify). */
    private val pairingStore: app.ember.airreceiver.airplay.pairing.PairingStore? = null,
    /** Shows ([pin]) or hides (null) the on-screen pairing PIN during SRP pair-setup. */
    private val onShowPin: (pin: String?) -> Unit = {},
    private val onAudioFlush: (firstSequence: Int?) -> Unit = {},
    private val pinPolicy: () -> AirPlayPinPolicy = {
        if (pinAuthEnabled) AirPlayPinPolicy.REMEMBER else AirPlayPinPolicy.OFF
    },
    private val mirrorFormat: () -> MirrorFormat = { MirrorFormat(displayWidth, displayHeight, advertisedFps, hevcEnabled) },
    private val approveAudioOnly: (String) -> Boolean = { true },
    private val allowHandoff: () -> Boolean = { false },
    private val createMirrorSession: ((MirrorFormat) -> app.ember.airreceiver.airplay.mirror.AirPlayMirrorSession)? = null,
    private val onActiveMirrorChanged: (app.ember.airreceiver.airplay.mirror.AirPlayMirrorSession?) -> Unit = {},
    private val onPlaybackReplaced: () -> Unit = onStreamingStopped
) {

    private val senderHls = SenderHlsTransport(onVideoPlay, onVideoStop)
    private var visiblePinSource: Control? = null

    private inner class Control(
        val sessionState: RtspSessionState,
        private val socket: java.net.Socket?,
        private val format: MirrorFormat,
        private val policy: AirPlayPinPolicy
    ) {
        private val mirror = if (socket == null) null else createMirrorSession?.invoke(format)
        private var remoteIdentity: Pair<String?, String?>? = null
        private var pendingVolume: Float? = null

        private fun owns() = socket == null || server.ownsPlayback(socket)

        private fun finish() {
            if (socket == null) onStreamingStopped() else server.finishPlayback(socket)
        }

        private fun activate(): Boolean {
            if (socket == null) return admitPlayback()
            val alreadyPlaying = owns()
            if (!server.claimPlayback(socket)) return false
            if (!alreadyPlaying) {
                onActiveMirrorChanged(mirror)
                remoteIdentity?.let { (id, token) -> onRemoteControlInfo(id, token) }
                pendingVolume?.let(onVolume)
            }
            return true
        }

        fun close() {
            pairingHandler.endSession()
            mirror?.releaseStreams()
            mirror?.releaseSession()
        }

        /** The classic RTSP verbs: OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN, PAUSE and photo. */
        val verbHandler: RtspVerbHandler by lazy {
            RtspVerbHandler(
                sessionState = sessionState,
                audioOnlyMode = audioOnlyMode,
                onStreamingStarted = onStreamingStarted,
                onStreamingStopped = { if (owns()) finish() },
                onPhotoReceived = onPhotoReceived,
                onPhotoCleared = { if (owns()) onPhotoCleared() },
                onMirrorAudioStop = { if (owns()) mirror?.stopMirrorAudio() ?: onMirrorAudioStop() },
                onMirrorVideoStop = { if (owns()) mirror?.stopMirrorVideo() ?: onMirrorVideoStop() },
                onBufferedAudioStop = { if (owns()) mirror?.stopBufferedAudio() ?: onBufferedAudioStop() },
                onFullTeardown = {
                    sessionState.mirrorKeysReady = false
                    sessionState.audioModeApproved = false
                    if (policy == AirPlayPinPolicy.EACH_SESSION) pairingHandler.endSession()
                },
                admitPlayback = ::activate,
                approveAudioOnly = approveAudioOnly
            )
        }

        /** The AirPlay 2 pairing endpoints: pair-setup, pair-verify and the legacy PIN flow. */
        val pairingHandler: RtspPairingHandler by lazy {
            RtspPairingHandler(
                verbs = verbHandler,
                context = context,
                sessionState = sessionState,
                pinAuthEnabled = pinAuthEnabled,
                pairingStore = pairingStore,
                onShowPin = { pin ->
                    if (pin != null) {
                        visiblePinSource = this
                        onShowPin(pin)
                    } else if (visiblePinSource === this) {
                        visiblePinSource = null
                        onShowPin(null)
                    }
                },
                pinPolicy = { policy }
            )
        }

        /** The AirPlay 2 mirroring endpoints: the binary-plist SETUP and the FairPlay fp-setup. */
        private val mirrorHandler: RtspMirrorHandler by lazy {
            RtspMirrorHandler(
                sessionState = sessionState,
                audioEnabled = audioEnabled,
                audioOnlyMode = audioOnlyMode,
                onMirrorSetupKeys = { key, secret, iv, peer, port ->
                    mirror?.startMirrorKeys(key, secret, iv, peer, port) ?: onMirrorSetupKeys(key, secret, iv, peer, port)
                },
                onMirrorStreamStart = { mirror?.startMirrorStream(it) ?: onMirrorStreamStart(it) },
                onMirrorAudioStart = { sr, ch, ct, spf -> mirror?.startMirrorAudio(sr, ch, ct, spf) ?: onMirrorAudioStart(sr, ch, ct, spf) },
                onBufferedAudioStart = { mirror?.startBufferedAudio() ?: onBufferedAudioStart() },
                admitPlayback = ::activate,
                approveAudioOnly = approveAudioOnly
            )
        }

        /** The media parameters: volume, DMAP now-playing metadata and album artwork. */
        private val parameterHandler: RtspParameterHandler by lazy {
            RtspParameterHandler(
                onVolume = { volume ->
                    pendingVolume = volume
                    if (owns()) onVolume(volume)
                },
                onNowPlayingMetadata = { title, artist, album -> if (owns()) onNowPlayingMetadata(title, artist, album) },
                onArtwork = { if (owns()) onArtwork(it) }
            )
        }

        /** The AirPlay URL-video mode: /play, /rate, /scrub, /stop and /playback-info. */
        private val videoUrlHandler: RtspVideoUrlHandler by lazy {
            RtspVideoUrlHandler(
                onVideoPlay = onVideoPlay,
                onVideoRate = { if (owns()) onVideoRate(it) },
                onVideoScrub = { if (owns()) onVideoScrub(it) },
                onVideoStop = {
                    if (owns()) {
                        senderHls.stopPlayback()
                        onVideoStop()
                    }
                },
                onPlaybackInfo = { if (owns()) onPlaybackInfo() else null },
                admitPlayback = ::activate,
                onSenderPlaylist = { request, url, offset ->
                    senderHls.play(request, sessionState.currentRemoteAddress, url, offset)
                }
            )
        }

        /** The receiver identity and session acknowledgement endpoints. */
        private val deviceInfoHandler: RtspDeviceInfoHandler by lazy {
            RtspDeviceInfoHandler(
                context = context,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                advertisedFps = advertisedFps,
                audioOnlyMode = audioOnlyMode,
                hevcEnabled = hevcEnabled,
                pinAuthEnabled = pinAuthEnabled,
                pinRequired = { policy != AirPlayPinPolicy.OFF },
                mirrorFormat = { format }
            )
        }

        /** Maps an incoming request to the handler that answers it. */
        val router: RtspRequestRouter by lazy {
            RtspRequestRouter(
                verbs = verbHandler,
                audioOnlyMode = audioOnlyMode,
                mirrorHandler = mirrorHandler,
                pairingHandler = pairingHandler,
                parameterHandler = parameterHandler,
                videoUrlHandler = videoUrlHandler,
                deviceInfoHandler = deviceInfoHandler,
                onRemoteControlInfo = { id, token ->
                    remoteIdentity = id to token
                    if (owns()) onRemoteControlInfo(id, token)
                },
                onAudioFlush = { sequence ->
                    if (owns()) {
                        mirror?.flushAudio(sequence)
                        onAudioFlush(sequence)
                    }
                }
            )
        }

    }

    private val standalone by lazy {
        Control(RtspSessionState(), null, mirrorFormat(), pinPolicy())
    }

    internal fun disconnectSender(): Boolean {
        senderHls.stopPlayback()
        val client = activeClient ?: return false
        runCatching { client.close() }
        return true
    }

    internal fun cancelPlaylistPlayback() = senderHls.stopPlayback()

    private val server: RtspServer by lazy {
        RtspServer(
            context = context,
            newControl = { socket, state ->
                val control = Control(state, socket, mirrorFormat(), pinPolicy())
                RtspServer.Connection(control.router::routeRequest, control::close)
            },
            senderHls = senderHls,
            onStreamingStopped = {
                onStreamingStopped()
                onActiveMirrorChanged(null)
            },
            onPlaybackReplaced = {
                onPlaybackReplaced()
                onActiveMirrorChanged(null)
            },
            admitPlayback = admitPlayback,
            allowHandoff = allowHandoff
        )
    }

    // ─── Transport state (owned by [RtspServer]) ────────────────────────────

    /** True while the RTSP server accepts senders. */
    private var running: Boolean
        get() = server.running
        set(value) { server.running = value }

    /** The socket of the sender that is currently connected. */
    private var activeClient: java.net.Socket?
        get() = server.activeClient
        set(value) { server.activeClient = value }

    /** The listening socket of the RTSP server. */
    private var serverSocket: java.net.ServerSocket?
        get() = server.serverSocket
        set(value) { server.serverSocket = value }

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [app.ember.airreceiver.airplay.AirPlayReceiver] after RECORD — wires to [app.ember.airreceiver.airplay.mirror.VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)?
        get() = server.onVideoNalUnit
        set(value) { server.onVideoNalUnit = value }

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes: MutableSet<Int>
        get() = standalone.sessionState.activeStreamTypes

    /** Starts the RTSP server. */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            server.runServer(this)
        }
    }

    /** Stops the RTSP server. */
    fun stop() {
        running = false
        // Close the two sockets separately: a failing client close must not leave
        // port 7000 bound for the next start.
        releaseQuietly("RTSP client socket") { activeClient?.close() }
        releaseQuietly("RTSP server socket") { serverSocket?.close() }
        server.closeAcceptedSockets()
        senderHls.close()
        activeClient = null
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

    // ─── Classic RTSP verbs (implemented by [RtspVerbHandler]) ──────────────

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handleOptionsInternal(request)

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handleAnnounceInternal(request)

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handleSetupInternal(request)

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handleRecordInternal(request)

    /** Handles TEARDOWN — stops one or all streams of the session. */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handleTeardownInternal(request)

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handleUnknownInternal(request)

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handlePauseInternal(request)

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handlePhotoPutInternal(request)

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse =
        standalone.verbHandler.handlePhotoDeleteInternal(request)
}
