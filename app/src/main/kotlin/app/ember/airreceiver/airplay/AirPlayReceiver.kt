// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay

import android.content.Context
import android.view.Surface
import app.ember.airreceiver.airplay.discovery.MdnsService
import app.ember.airreceiver.airplay.mirror.AirPlayMirrorSession
import app.ember.airreceiver.airplay.nowplaying.DacpClient
import app.ember.airreceiver.airplay.nowplaying.NowPlayingInfo
import app.ember.airreceiver.airplay.nowplaying.NowPlayingState
import app.ember.airreceiver.airplay.playback.AirPlayUrlSession
import app.ember.airreceiver.airplay.playback.PhotoImageType
import app.ember.airreceiver.airplay.rtsp.RtspHandler
import app.ember.airreceiver.airplay.rtsp.SessionDescription
import app.ember.airreceiver.airplay.timing.TimingHandler
import app.ember.airreceiver.service.ProtocolState
import app.ember.airreceiver.util.Logger
import app.ember.airreceiver.util.releaseQuietly
import app.ember.airreceiver.util.rethrowIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [app.ember.airreceiver.airplay.mirror.VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [app.ember.airreceiver.airplay.audio.AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [AirReceiverService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * The heavy lifting lives in three collaborators, so that every file stays small:
 * - [AirPlayMediaPipeline]: the classic media pipeline (video decode, audio decode, audio UDP)
 * - [AirPlayMirrorSession]: the AirPlay 2 mirroring streams (keys, event channel, NTP, servers)
 * - [AirPlayUrlSession]: the non-mirroring video URL mode
 * - [NowPlayingState]: the "now playing" metadata and the audio/video flags behind it
 *
 * For audio-only streams (music, podcasts), only [app.ember.airreceiver.airplay.audio.AudioPlayer] is started —
 * no [app.ember.airreceiver.airplay.mirror.VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [AirReceiverService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val lowBandwidthMirroring: Boolean = false,
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([AirReceiverService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /**
     * Called with whether the session that just started carries **video**.
     *
     * WHY: The service brings the streaming screen to the front when a session connects
     * while the app is in the background — but only mirroring sessions need a Surface.
     * An audio-only session (such as a music or podcast stream) must not pull the TV out of
     * whatever the user was watching, so the caller needs to tell the two apart.
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onVideoSessionChanged: (hasVideo: Boolean) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, music or podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /** Receives the session-progress summary when a session ends (for the diagnostics screen). */
    private val onSessionSummary: (String) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {}
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = app.ember.airreceiver.airplay.pairing.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)

    // Now-playing state — shared by the orchestrator, the mirror session and the URL session.
    private val nowPlaying = NowPlayingState(onNowPlayingChanged)

    // Records how far the current session got (pairing → keys → stream setup → media), so a
    // failure is logged with the stage it stopped at instead of a bare ERROR. See SessionProgress.
    private val sessionProgress = SessionProgress()

    // Classic AirPlay media pipeline: video decoder, audio player, audio UDP socket.
    private val mediaPipeline = AirPlayMediaPipeline(scope, videoSurfaceProvider)

    private val advertisedFps by lazy {
        if (lowBandwidthMirroring) 30
        else app.ember.airreceiver.airplay.mirror.MirrorCapabilities.frameRate(context, mirrorWidth, mirrorHeight)
    }
    private val hevcEnabled by lazy {
        app.ember.airreceiver.airplay.mirror.MirrorCapabilities.supportsHevc(mirrorWidth, mirrorHeight, advertisedFps)
    }

    // AirPlay 2 mirroring: keys, event channel, NTP, mirror data/audio servers.
    private val mirrorSession = AirPlayMirrorSession(
        scope = scope,
        videoSurfaceProvider = videoSurfaceProvider,
        mirrorWidth = mirrorWidth,
        mirrorHeight = mirrorHeight,
        nowPlaying = nowPlaying,
        onSenderNameChanged = { name -> onSenderNameChanged(name) },
        onVideoSessionChanged = { hasVideo -> onVideoSessionChanged(hasVideo) },
        hevcEnabled = hevcEnabled,
        advertisedFps = advertisedFps,
        onStateChanged = ::emitState,
        onVideoFailure = { reason ->
            sessionProgress.fail(reason)
            onSessionSummary(sessionProgress.summary())
        }
    )

    // AirPlay video URL mode (non-mirroring).
    private val urlSession = AirPlayUrlSession(
        videoSurfaceProvider = videoSurfaceProvider,
        onSenderNameChanged = { name -> onSenderNameChanged(name) },
        onVideoSessionChanged = { hasVideo -> onVideoSessionChanged(hasVideo) },
        onStateChanged = { state -> emitState(state) },
        onStreamingStopped = { onStreamingStopped() },
        cancelPlaylistPlayback = { rtspHandler?.cancelPlaylistPlayback() }
    )

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        // WHY the display name is not logged: it is the name the owner gave their
        // television. The reduction in util/LogRedactor knows it as well, so the second
        // line of defence is in place — but the name does not belong in a log excerpt
        // that may end up in a public issue tracker.
        Logger.i("AirPlayReceiver starting")
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [AirReceiverService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        // Each component is released on its own: one failing stop() must not leave the
        // RTSP port bound or the mDNS record published for the next start.
        try {
            releaseQuietly("RTSP handler") { rtspHandler?.stop() }
            releaseQuietly("Timing handler") { timingHandler?.stop() }
            releaseQuietly("mDNS service") { mdnsService?.stop() }
            releaseQuietly("DACP client") { dacpClient.stop() }
            releaseQuietly("Media components") { releaseMediaComponents() }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    internal fun canControlSender() = dacpClient.canControl()
    internal fun urlPlayback() = urlSession.info()
    internal fun urlRate(rate: Float) = urlSession.setRate(rate)
    internal fun urlSeek(seconds: Double) = urlSession.scrub(seconds)
    internal fun stopUrlPlayback() = urlSession.stopUrlVideo()

    /** True once a sender has advertised DACP reverse-control (so the TV remote can drive playback). */
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    /**
     * Re-registers the mDNS advertisement without touching the RTSP/timing servers.
     *
     * For when the network came back under us — the TV waking from standby being the case that
     * prompted this. An NsdManager registration does not survive its interface going down, and
     * nothing brings it back on its own, so the receiver keeps listening on port 7000 while
     * being invisible in every AirPlay picker. Restarting the whole receiver would do it too,
     * but that needlessly drops the sockets; only the advertisement is stale.
     *
     * No-op when the receiver was never started.
     */
    fun readvertise() {
        val mdns = mdnsService ?: return
        Logger.i("Re-advertising AirPlay over mDNS (network returned)")
        scope.launch {
            try {
                mdns.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Logger.e("Failed to re-advertise mDNS", e)
            }
        }
    }

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            hevcEnabled = hevcEnabled,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            advertisedFps = advertisedFps,
            hevcEnabled = hevcEnabled,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                // Transport milestones only — a non-empty ECDH secret means pair-verify succeeded
                // and a 16-byte key means the FairPlay key exchange returned one; neither says
                // protected content can play (see SessionProgress).
                sessionProgress.mark(SessionProgress.Milestone.SENDER_CONNECTED)
                if (ecdhSecret.isNotEmpty()) sessionProgress.mark(SessionProgress.Milestone.PAIR_VERIFIED)
                if (aesKey.size == 16) sessionProgress.mark(SessionProgress.Milestone.FAIRPLAY_KEY_READY)
                mirrorSession.startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { id ->
                sessionProgress.mark(SessionProgress.Milestone.VIDEO_STREAM_SETUP); mirrorSession.startMirrorStream(id)
            },
            onMirrorAudioStart = { sr, ch, ct, spf ->
                sessionProgress.mark(SessionProgress.Milestone.AUDIO_STREAM_SETUP); mirrorSession.startMirrorAudio(sr, ch, ct, spf)
            },
            onAudioFlush = { firstSequence ->
                mirrorSession.flushAudio(firstSequence)
                mediaPipeline.flushAudio(firstSequence)
            },
            onMirrorAudioStop = { mirrorSession.stopMirrorAudio() },
            onMirrorVideoStop = { mirrorSession.stopMirrorVideo() },
            onBufferedAudioStart = {
                sessionProgress.mark(SessionProgress.Milestone.BUFFERED_STREAM_SETUP); mirrorSession.startBufferedAudio()
            },
            onBufferedAudioStop = { mirrorSession.stopBufferedAudio() },
            onVolume = ::setSessionVolume,
            onNowPlayingMetadata = { title, artist, album ->
                nowPlaying.npTitle = title; nowPlaying.npArtist = artist; nowPlaying.npAlbum = album
                nowPlaying.emitNowPlaying()
            },
            onArtwork = { bytes ->
                nowPlaying.npArtwork = bytes.takeIf { it.isNotEmpty() }
                nowPlaying.emitNowPlaying()
            },
            onVideoPlay = { url, start -> urlSession.startUrlVideo(url, start) },
            onVideoRate = { rate -> urlSession.setRate(rate) },
            onVideoScrub = { pos -> urlSession.scrub(pos) },
            onVideoStop = { urlSession.stopUrlVideo() },
            onPlaybackInfo = { urlSession.info() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) }
        ).also {
            it.start(scope)
            mediaPipeline.rtspHandler = it
        }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [app.ember.airreceiver.airplay.mirror.VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [app.ember.airreceiver.airplay.audio.AudioPlayer]
     * - audio-only:   only [app.ember.airreceiver.airplay.audio.AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")
        // A legacy (SDP/RAOP) session never passes through the mirror key callback, so make sure
        // the timeline has at least begun before the media stage is recorded.
        sessionProgress.mark(SessionProgress.Milestone.SENDER_CONNECTED)

        scope.launch {
            try {
                if (session.hasVideo) mediaPipeline.startVideoDecoder(session)
                if (session.hasAudio) mediaPipeline.startAudioPlayer(session)
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                nowPlaying.npSenderName = session.senderName.ifBlank { nowPlaying.npSenderName }
                nowPlaying.videoPlaying = session.hasVideo
                nowPlaying.audioPlaying = session.hasAudio
                nowPlaying.emitNowPlaying()
                // Notify AirReceiverService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                // Same for the stream kind: an audio-only session must not pull the
                // streaming screen to the front.
                onVideoSessionChanged(session.hasVideo)
                sessionProgress.mark(SessionProgress.Milestone.MEDIA_STARTED)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                sessionProgress.fail("media pipeline: ${e.javaClass.simpleName}")
                val summary = sessionProgress.summary()
                Logger.e("Failed to start media pipeline — $summary", e)
                onSessionSummary(summary)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components and re-advertises so the device reappears
     * in sender pickers immediately.
     */
    private fun onStreamingStopped() {
        // Log how far this session got before it ended — the one place to see whether a session
        // that produced no picture got stuck at pairing, keys, stream setup or media.
        val summary = sessionProgress.summary()
        Logger.i("Streaming stopped — $summary")
        Logger.d { sessionProgress.timeline() }
        onSessionSummary(summary)
        sessionProgress.begin()   // ready for the next session
        releaseMediaComponents()
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Clears the video NAL callback, closes the audio socket, and releases media components.
     *
     * The order of the steps is the original one: the NAL callback is detached first, then the
     * audio socket is closed, then the mirror/URL players are stopped and only afterwards the
     * decoders are released — a decoder must not be freed while a player may still render.
     */
    private fun setSessionVolume(volume: Float) {
        mirrorSession.setVolume(volume)
        mediaPipeline.setVolume(volume)
        urlSession.setVolume(volume)
    }

    private fun releaseMediaComponents() {
        mediaPipeline.stopVideoFeed()
        mediaPipeline.closeAudioSocket()
        mirrorSession.releaseStreams()
        urlSession.release()
        mirrorSession.releaseSession()
        mediaPipeline.releaseDecoders()
        setSessionVolume(0f)
        nowPlaying.reset()
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch(Dispatchers.Main.immediate) { onStateChanged(state) }
    }
}
