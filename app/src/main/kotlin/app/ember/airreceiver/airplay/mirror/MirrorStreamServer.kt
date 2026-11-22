// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.mirror

import android.view.Surface
import app.ember.airreceiver.airplay.StreamStats
import app.ember.airreceiver.airplay.pairing.MirrorCrypto
import app.ember.airreceiver.util.Logger
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * The queue is bounded twice: by item count (latency) and by total bytes (memory) — see
 * [MirrorFrameQueue] and the security review 2026-10-24, §3.3, finding 2.
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val maxQueueBytes: Long = DEFAULT_MAX_QUEUE_BYTES,
    private val maxQueueItems: Int = DEFAULT_MAX_QUEUE_ITEMS,
    private val hevcEnabled: Boolean = false,
    private val onFrameRendered: () -> Unit = {},
    private val timingClient: app.ember.airreceiver.airplay.timing.AirPlayNtpClient? = null,
) {
    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    // OS-assigned free port, explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
    private val queue = MirrorFrameQueue(maxItems = maxQueueItems, maxBytes = maxQueueBytes)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var lastVps: ByteArray? = null
    private var decoderSetupFailures = 0
    private var decoderUnavailable = false
    private var failedCodecName: String? = null
    private var activeCodecToAvoid: String? = null
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var firstFrameSubmitted = false
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        StreamStats.resetVideo()
        StreamStats.videoStage = "waiting connection"
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            StreamStats.videoStage = "waiting config"
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            val headerFields = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        StreamStats.videoReceived.incrementAndGet()
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) {
                            val timestamp = headerFields.getLong(8)
                            enqueue(MirrorFrameQueue.Frame(annexB, timestamp))
                        } else {
                            StreamStats.videoInvalid.incrementAndGet()
                        }
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> Logger.v { "Mirror: ignoring payload type $payloadType ($payloadSize B)" }
                }
            }
        } catch (e: Exception) {
            if (running) {
                StreamStats.videoError("reader failed", e)
                Logger.e("Mirror reader error", e)
            }
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /**
     * Enqueues an item for the decoder thread.
     *
     * TWO BOUNDS ARE ENFORCED BY THE QUEUE (see [MirrorFrameQueue]):
     *  - the item count (latency: ~1.5 s of video), and
     *  - the total number of bytes held in the queue (memory).
     *
     * WHY THE BYTE BOUND: the item count alone is not enough. A sender on the LAN may push
     * payloads of up to [MAX_PAYLOAD] (8 MB) each; 90 such frames would keep ~720 MB alive and
     * OOM the app process. The byte budget caps the memory a hostile or broken sender can make
     * the receiver hold, independently of how many frames that is.
     *
     * The reader thread NEVER blocks here: if the decoder is behind, the oldest frames are
     * dropped so that the socket keeps being drained (macOS would drop the whole session
     * otherwise).
     */
    private fun enqueue(item: MirrorFrameQueue.Item) {
        framesIn++
        val outcome = queue.offer(item)
        if (!outcome.accepted) {
            // The item alone is bigger than the whole memory budget — it can never be queued.
            framesDropped++
            awaitingKeyframe = true
            Logger.w("Mirror: dropped an oversized item (${item.sizeBytes} B > queue budget)")
        } else if (outcome.dropped > 0) {
            framesDropped += outcome.dropped
            awaitingKeyframe = true      // a frame was lost — resync the decoder at the next IDR
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$maxQueueItems " +
                "${queue.byteSize}B/${maxQueueBytes}B ${StreamStats.videoFps}fps")
        }
    }

    /** Parses a type-1 payload; a malformed record is reported and skipped (see [parseConfigRecord]). */
    private fun parseConfig(payload: ByteArray): MirrorFrameQueue.Config? {
        StreamStats.videoConfigs.incrementAndGet()
        return try {
            val hevc = payload.size >= 8 && payload.decodeToString(4, 8) == "hvc1"
            if (hevc) {
                require(hevcEnabled) { "HEVC was not advertised" }
                parseHevcConfig(payload)
            } else parseConfigRecord(payload)
        } catch (e: IllegalArgumentException) {
            StreamStats.videoError("config parse failed", e)
            Logger.w("Mirror: ignoring malformed SPS/PPS record — ${e.message}")
            null
        }
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(20)
                decoder?.drainPendingOutput()
                if (item == null) continue
                StreamStats.videoQueue = queue.size
                when (item) {
                    is MirrorFrameQueue.Config -> configureDecoder(item.sps, item.pps, item.vps)
                    is MirrorFrameQueue.Frame -> decodeFrame(item.annexB, item.senderTimestamp)
                }
            }
        } catch (e: Exception) {
            if (running) {
                StreamStats.videoError("decoder thread failed", e)
                Logger.e("Mirror decoder thread error", e)
            }
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray, vps: ByteArray?) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        val changed = !sps.contentEquals(lastSps) || !pps.contentEquals(lastPps) || !vps.contentEquals(lastVps)
        if (d != null && d.isHealthy && !changed && surface === configuredSurface) return
        if (changed) resetDecoderRecovery()
        lastSps = sps
        lastPps = pps
        lastVps = vps
        rebuildDecoder(surface)
    }

    private fun resetDecoderRecovery() {
        decoderSetupFailures = 0
        failedCodecName = null
        activeCodecToAvoid = null
        decoderUnavailable = false
        StreamStats.videoDecoderUnavailable = false
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        if (surface !== configuredSurface) resetDecoderRecovery()
        configuredSurface = surface
        if (decoderUnavailable) return
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) {
            StreamStats.videoStage = "waiting surface"
            return
        }
        val sc = MirrorCrypto.START_CODE
        StreamStats.videoRebuilds.incrementAndGet()
        val alternate = if (decoderSetupFailures >= 2) failedCodecName else activeCodecToAvoid
        val replacement = VideoDecoder(surface).apply {
            onFrameRendered = { if (running && decoder === this) this@MirrorStreamServer.onFrameRendered() }
            useMonotonicPresentationTime = true
            codecToAvoid = alternate
        }
        try {
            val vps = lastVps
            if (vps == null) replacement.initialize(sc + sps, sc + pps, width, height)
            else replacement.initializeHevc(sc + vps + sc + sps + sc + pps, width, height)
        } catch (e: Exception) {
            decoderSetupFailures++
            if (alternate == null) {
                failedCodecName = StreamStats.videoCodec.takeIf { it.isNotBlank() }
            }
            val unavailable = decoderSetupFailures >= 3 ||
                (decoderSetupFailures >= 2 && failedCodecName == null)
            decoderUnavailable = unavailable
            StreamStats.videoDecoderUnavailable = unavailable
            StreamStats.videoError(if (unavailable) "decoder unavailable" else "decoder setup failed; waiting for IDR", e)
            Logger.w("Mirror: decoder setup failed (attempt $decoderSetupFailures, alternate=${alternate != null})")
            return
        }
        decoder = replacement
        activeCodecToAvoid = alternate
        decoderSetupFailures = 0
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(annexB: ByteArray, senderTimestamp: Long) {
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        StreamStats.videoSurface = if (liveSurface == null) "missing" else
            "valid=${liveSurface.isValid} id=${System.identityHashCode(liveSurface)}"
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        } else if (decoder == null && !decoderUnavailable &&
            liveSurface != null && lastSps != null && isKeyframe(annexB)) {
            // A failed setup may be transient. Retry at a clean decode point on the same surface.
            rebuildDecoder(liveSurface)
        }
        var d = decoder ?: run {
            StreamStats.videoSkipped.incrementAndGet()
            return
        }
        if (!d.isHealthy) {                                    // error state — drop, await next config
            if (!isKeyframe(annexB)) {
                StreamStats.videoStage = "unhealthy: waiting IDR"
                StreamStats.videoSkipped.incrementAndGet()
                return
            }
            StreamStats.videoStage = "unhealthy: rebuilding"
            StreamStats.videoSkipped.incrementAndGet()
            Logger.w("Mirror: decoder unhealthy — rebuilding with cached SPS/PPS")
            rebuildDecoder(liveSurface)
            d = decoder ?: return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) {
                StreamStats.videoStage = "waiting IDR"
                StreamStats.videoSkipped.incrementAndGet()
                return
            }
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        val nowNs = System.nanoTime()
        val mappedNs = timingClient?.mirrorPresentationNanos(senderTimestamp, nowNs)
        // Fall back to arrival pacing for missing or discontinuous sender timestamps.
        val ptsUs = (mappedNs?.takeIf { it - nowNs in -500_000_000L..50_000_000L } ?: nowNs) / 1000
        if (!firstFrameSubmitted) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        if (d.decodeNalUnit(annexB, ptsUs)) {
            firstFrameSubmitted = true
        } else {
            awaitingKeyframe = true
            StreamStats.videoStage = "input dropped: waiting IDR"
            // Losing a new configuration's first IDR can freeze until the next rotation.
            // Retry it once on a fresh codec, with the current cached configuration.
            if (isKeyframe(annexB)) {
                StreamStats.videoKeyframeRetries.incrementAndGet()
                rebuildDecoder(liveSurface)
                val fresh = decoder
                if (fresh != null && fresh.decodeNalUnit(annexB, ptsUs)) {
                    awaitingKeyframe = false
                    firstFrameSubmitted = true
                }
            }
        }
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                val header = annexB[i + 3].toInt()
                if (lastVps == null && header and 0x1f == 5) return true
                if (lastVps != null && (header ushr 1 and 0x3f) in 16..21) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        /**
         * Parses the avcC-style SPS/PPS record of a mirroring type-1 payload.
         *
         * Every length read from the sender is checked against the payload before it is used
         * (SEC-01), so a malformed record fails with a clear reason instead of an unchecked
         * array index.
         *
         * @throws IllegalArgumentException when a length field points outside the payload.
         */
        internal fun parseConfigRecord(payload: ByteArray): MirrorFrameQueue.Config {
            require(payload.size >= CONFIG_HEADER_BYTES) { "record of ${payload.size} B is too short" }
            val spsSize = u16(payload, CONFIG_HEADER_BYTES - 2)
            val ppsLenOffset = CONFIG_HEADER_BYTES + spsSize + 1   // skip the 1-byte PPS count
            require(spsSize > 0 && ppsLenOffset + 2 <= payload.size) { "SPS length $spsSize out of range" }
            val ppsSize = u16(payload, ppsLenOffset)
            val ppsEnd = ppsLenOffset + 2 + ppsSize
            require(ppsSize > 0 && ppsEnd <= payload.size) { "PPS length $ppsSize out of range" }
            return MirrorFrameQueue.Config(
                payload.copyOfRange(CONFIG_HEADER_BYTES, CONFIG_HEADER_BYTES + spsSize),
                payload.copyOfRange(ppsLenOffset + 2, ppsEnd)
            )
        }

        /** Extract the decoder arrays from a HEVC visual sample entry. */
        internal fun parseHevcConfig(payload: ByteArray): MirrorFrameQueue.Config {
            require(payload.size in 94..65_536) { "Invalid HEVC sample description size" }
            val bytes = java.nio.ByteBuffer.wrap(payload)
            var box = 86 // VisualSampleEntry fields, including its outer box header
            while (box <= payload.size - 8) {
                val size = bytes.getInt(box)
                require(size in 8..(payload.size - box)) { "Invalid HEVC child box" }
                if (payload.decodeToString(box + 4, box + 8) == "hvcC") {
                    val end = box + size
                    val start = box + 8
                    require(end - start >= 23 && payload[start] == 1.toByte()) { "Invalid HEVC decoder record" }
                    require(payload[start + 21].toInt() and 3 == 3) { "Unsupported HEVC NAL length" }
                    val arrays = payload[start + 22].toInt() and 0xff
                    var offset = start + 23
                    val sets = mutableMapOf<Int, ByteArray>()
                    repeat(arrays) {
                        require(offset + 3 <= end) { "Truncated HEVC array" }
                        val type = payload[offset].toInt() and 0x3f
                        val count = u16(payload, offset + 1)
                        offset += 3
                        repeat(count) {
                            require(offset + 2 <= end) { "Truncated HEVC NAL length" }
                            val length = u16(payload, offset)
                            offset += 2
                            require(length in 2..(end - offset)) { "Invalid HEVC NAL size" }
                            if (type in 32..34 && type !in sets) sets[type] = payload.copyOfRange(offset, offset + length)
                            offset += length
                        }
                    }
                    require(sets.keys.containsAll(listOf(32, 33, 34))) { "Missing HEVC parameter sets" }
                    return MirrorFrameQueue.Config(sets.getValue(33), sets.getValue(34), sets.getValue(32))
                }
                box += size
            }
            throw IllegalArgumentException("Missing HEVC decoder record")
        }

        private fun u16(b: ByteArray, offset: Int): Int =
            ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val CONFIG_HEADER_BYTES = 8              // avcC prefix before the SPS bytes
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L

        /**
         * Default memory budget of the frame queue.
         *
         * WHY 32 MB: a real 1080p–1440p frame is typically 50–500 KB, so 32 MB holds more frames
         * than the item limit — a healthy stream is never affected by the budget, only
         * pathological payloads are. It is also far below the 150 MB process budget of NFR-01.
         * The previous count-only bound allowed ~720 MB.
         */
        const val DEFAULT_MAX_QUEUE_BYTES = MirrorFrameQueue.DEFAULT_MAX_BYTES

        /** Default item limit of the frame queue (~1.5 s of video at 60 fps). */
        const val DEFAULT_MAX_QUEUE_ITEMS = MirrorFrameQueue.DEFAULT_MAX_ITEMS
    }
}
