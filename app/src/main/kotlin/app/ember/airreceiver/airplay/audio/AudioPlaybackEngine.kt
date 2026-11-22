// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.audio

import app.ember.airreceiver.util.PlaybackFocus
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.Build
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.ember.airreceiver.airplay.StreamStats
import app.ember.airreceiver.airplay.pairing.MirrorCrypto
import app.ember.airreceiver.util.Logger
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AudioPlaybackEngine — Decrypts, decodes and plays the queued audio frames.
 *
 * WHAT: Owns the AES key, the MediaCodec decoder, the software ALAC decoder and the
 * AudioTrack. It takes the decrypted-pending payloads out of the jitter buffer, decrypts
 * them, decodes them to PCM and writes that PCM to the speaker.
 *
 * WHY: The playback path has a hard rule — the thread that creates the MediaCodec and the
 * AudioTrack must also release them. Releasing a codec from another thread crashes the
 * whole process with a native SIGABRT ("pthread_mutex_destroy called on a destroyed mutex"
 * inside libstagefright). Keeping the decoder and the track in one class makes that
 * ownership obvious: this class creates them in [runPlayback] and releases them in the
 * same method's finally block.
 *
 * HOW: The server launches [runPlayback] on its own coroutine and hands the engine the
 * shared jitter buffer. The loop stops as soon as [isRunning] reports false; the finally
 * block then releases the codec, the ALAC decoder and the AudioTrack.
 *
 * Example:
 *   val engine = AudioPlaybackEngine(aesKey, ecdhSecret, aesIv, 44100, 2, 8, 480, queue) { running }
 *   scope.launch(Dispatchers.IO) { engine.runPlayback() }
 */
internal class AudioPlaybackEngine(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    aesIv: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
    private val codecType: Int,
    private val framesPerPacket: Int,
    /** Bounded jitter buffer filled by the receive thread. */
    private val frameQueue: ArrayBlockingQueue<RealtimeAudioFrame>,
    /** True while the audio session is running — the playback loop stops when it turns false. */
    private val presentationNanos: (Int) -> Long? = { null },
    private val isRunning: () -> Boolean
) {

    private val focus = PlaybackFocus()
    private val key = SecretKeySpec(MirrorCrypto.audioKey(aesKey, ecdhSecret), "AES")
    private val iv = IvParameterSpec(aesIv.copyOf(16))

    // Playback gain (0..1), set from the sender's AirPlay volume. Applied to the AudioTrack and
    // re-applied if the track is recreated. Starts at full.
    @Volatile private var volumeGain = 1f

    // Reused across packets: decryptPacket runs only on the playback thread, so one Cipher
    // instance is safe and avoids a Cipher.getInstance allocation on every packet (~92/s).
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")

    private var codec: MediaCodec? = null
    private var softwareDecoder = false
    private var alac: AlacDecoder? = null      // software ALAC decoder (ct=2 system-audio path)
    @Volatile private var audioTrack: AudioTrack? = null
    private var firstPcm = true
    private var outputSampleRate = sampleRate
    private var outputChannels = channels
    private val outputTimestamp = AudioTimestamp()
    private var writtenBytes = 0L
    private var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Playback thread: decrypt + decode queued frames and write PCM to AudioTrack. This thread is
     * the SOLE owner of [codec] and [audioTrack] — it creates them here and releases them in the
     * finally block, so no other thread ever touches the codec concurrently (see
     * AudioStreamServer.stop, which deliberately does NOT release them).
     */
    fun runPlayback() {
        try {
            initDecoder()
            if (alac != null) initAudioTrack()
            while (isRunning()) {
                val payload = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    if (payload === AudioReorderBuffer.FLUSH) {
                        // A fresh track starts a new playback-head epoch for the new RTP timeline.
                        releaseAudioTrack()
                        codec?.flush()
                        StreamStats.audioOutputDelayMs = null
                        continue
                    }
                    val decrypted = decryptPacket(payload.payload)
                    val ptsUs = presentationNanos(payload.rtpTime)?.div(1000) ?: 0L
                    if (alac != null) playAlacFrame(decrypted, ptsUs) else decodeWithRecovery(decrypted, ptsUs)
                } catch (e: Exception) {
                    if (isRunning()) Logger.e("Audio: frame decode error", e)
                }
            }
        } catch (e: Exception) {
            if (isRunning()) Logger.e("Audio playback error", e)
        } finally {
            // Release on the same thread that used the codec — never cross-thread (avoids SIGABRT).
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { alac?.close() }
            focus.release()
            releaseAudioTrack()
            codec = null
            alac = null
            Logger.i("AudioStreamServer stopped")
        }
    }

    /** Decode one decrypted ALAC frame to PCM and write it to AudioTrack (blocking, paces playback). */
    private fun playAlacFrame(frame: ByteArray, ptsUs: Long) {
        val pcm = alac?.decode(frame) ?: return
        if (firstPcm) { Logger.i("Audio: first decoded ALAC PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
        writePcm(pcm, ptsUs)
    }

    /** AES-128-CBC decrypt the whole-block portion; the trailing < 16 bytes stay cleartext. */
    private fun decryptPacket(payload: ByteArray): ByteArray {
        val encryptedLen = (payload.size / 16) * 16
        if (encryptedLen == 0) return payload
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)   // fresh IV per packet (RAOP)
        val out = payload.copyOf()
        cbcCipher.doFinal(payload, 0, encryptedLen, out, 0)
        return out
    }

    private fun decodeFrame(aac: ByteArray, ptsUs: Long) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            mc.getInputBuffer(inIdx)!!.apply { clear(); put(aac) }
            mc.queueInputBuffer(inIdx, 0, aac.size, ptsUs, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(info, 0)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = mc.outputFormat
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                if (rate != outputSampleRate || channelCount != outputChannels || encoding != outputEncoding) {
                    releaseAudioTrack()
                    outputSampleRate = rate
                    outputChannels = channelCount
                    outputEncoding = encoding
                }
                continue
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (outIdx < 0) return
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val outBuf = checkNotNull(mc.getOutputBuffer(outIdx))
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm)
                    if (firstPcm) {
                        Logger.i("Audio: first decoded PCM (${pcm.size}B) → AudioTrack")
                        firstPcm = false
                    }
                    writePcm(pcm, info.presentationTimeUs)
                }
            } finally {
                mc.releaseOutputBuffer(outIdx, false)
            }
        }
    }

    private fun decodeWithRecovery(frame: ByteArray, ptsUs: Long) {
        try {
            decodeFrame(frame, ptsUs)
        } catch (failure: IllegalStateException) {
            if (softwareDecoder) throw failure
            runCatching { codec?.release() }
            codec = null
            initAacDecoder(softwareOnly = true)
            decodeFrame(frame, ptsUs)
        }
    }

    private fun initDecoder() {
        if (codecType == AudioStreamServer.CT_ALAC) {
            // macOS sends ALAC (lossless) for system-audio AirPlay regardless of our advertised
            // formats, and this TV has no hardware ALAC codec — so we decode in software via the
            // bundled Apple ALAC decoder (libalac.so). frameLength comes from the SETUP spf.
            alac = AlacDecoder(sampleRate, channels, framesPerPacket)
            Logger.i("Audio decoder: ALAC ${sampleRate}Hz x$channels spf=$framesPerPacket (ct=2)")
            return
        }
        initAacDecoder(softwareOnly = false)
    }

    private fun initAacDecoder(softwareOnly: Boolean) {
        // ct=8 AAC-ELD (mirroring, spf 480) vs ct=4 AAC-LC (audio-only, spf 1024).
        val isAacLc = codecType == AudioStreamServer.CT_AAC_LC
        val profile = if (isAacLc) MediaCodecInfo.CodecProfileLevel.AACObjectLC
                      else MediaCodecInfo.CodecProfileLevel.AACObjectELD
        val asc = if (isAacLc) AudioStreamServer.buildAacLcAsc(sampleRate, channels)
                  else AudioStreamServer.buildAacEldAsc(sampleRate, channels)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        val profileName = if (isAacLc) "AAC-LC" else "AAC-ELD"
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !softwareOnly || it.isSoftwareDecoder() }
            .sortedBy { it.isSoftwareDecoder() }
        var lastFailure: Exception? = null
        for (info in codecs) {
            if (info.isEncoder || info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) }) {
                continue
            }
            var candidate: MediaCodec? = null
            try {
                val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                if (!info.isSoftwareDecoder() && (
                        capabilities.profileLevels.none { it.profile == profile } ||
                            !capabilities.isFormatSupported(format)
                    )) continue

                candidate = MediaCodec.createByCodecName(info.name)
                candidate.configure(format, null, null, 0)
                candidate.start()
                codec = candidate
                softwareDecoder = info.isSoftwareDecoder()
                Logger.i("Audio decoder: $profileName via ${info.name} (${sampleRate}Hz x$channels)")
                return
            } catch (e: Exception) {
                runCatching { candidate?.release() }
                lastFailure = e
                Logger.w("Audio: ${info.name} could not start $profileName")
            }
        }
        throw IllegalStateException("No usable $profileName decoder for ${sampleRate}Hz x$channels", lastFailure)
    }

    private fun MediaCodecInfo.isSoftwareDecoder(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) isSoftwareOnly
        else name.startsWith("c2.android.") || name.startsWith("OMX.google.")

    /** Pace the output in short steps so FLUSH can preempt delayed and partially written PCM. */
    private fun writePcm(pcm: ByteArray, ptsUs: Long) {
        if (!writingAllowed() || !focus.acquire()) return
        var track = audioTrack ?: initAudioTrack()
        if (!awaitPresentation(track, ptsUs * 1000L)) return

        var offset = 0
        var retried = false
        var lastProgress = System.nanoTime()
        while (offset < pcm.size && writingAllowed() && focus.acquire()) {
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            when {
                written > 0 -> {
                    offset += written
                    writtenBytes += written
                    lastProgress = System.nanoTime()
                }
                written == 0 && System.nanoTime() - lastProgress < 120_000_000L ->
                    LockSupport.parkNanos(2_000_000L)
                written == AudioTrack.ERROR_DEAD_OBJECT && !retried -> {
                    releaseAudioTrack()
                    track = initAudioTrack()
                    retried = true
                    lastProgress = System.nanoTime()
                }
                else -> {
                    releaseAudioTrack()
                    Logger.w("Audio output stopped accepting PCM ($written)")
                    return
                }
            }
        }
    }

    private fun writingAllowed(): Boolean =
        isRunning() && frameQueue.peek() !== AudioReorderBuffer.FLUSH

    private fun awaitPresentation(track: AudioTrack, ptsNanos: Long): Boolean {
        while (writingAllowed() && focus.acquire()) {
            val now = System.nanoTime()
            val timestamp = writtenBytes > 0 && runCatching { track.getTimestamp(outputTimestamp) }.getOrDefault(false)
            val queued = AudioPlayout.queuedNanos(
                writtenBytes / (outputChannels * 2), outputSampleRate, now,
                if (timestamp) outputTimestamp.framePosition else null,
                if (timestamp) outputTimestamp.nanoTime else null,
                track.playbackHeadPosition
            )
            StreamStats.audioOutputDelayMs = (queued / 1_000_000L).toInt()
            when (val wait = AudioPlayout.waitNanos(ptsNanos, now, queued)) {
                AudioPlayout.DROP_FRAME -> {
                    StreamStats.audioLateFrames.incrementAndGet()
                    return false
                }
                0L -> return true
                else -> LockSupport.parkNanos(wait)
            }
        }
        return false
    }

    private fun releaseAudioTrack() {
        val track = audioTrack
        audioTrack = null
        writtenBytes = 0
        runCatching { track?.stop() }
        runCatching { track?.release() }
    }

    /** Sets playback volume from the sender's AirPlay volume (−30 dB … 0 dB, or ≤ −144 = mute). */
    fun setVolume(airplayVolume: Float) {
        volumeGain = if (airplayVolume <= -144f) 0f else ((airplayVolume + 30f) / 30f).coerceIn(0f, 1f)
        runCatching { audioTrack?.setVolume(volumeGain) }
    }

    private fun initAudioTrack(): AudioTrack {
        val channelMask = when (outputChannels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Unsupported PCM channel count: $outputChannels")
        }
        check(outputEncoding == AudioFormat.ENCODING_PCM_16BIT) { "Unsupported PCM encoding: $outputEncoding" }
        val minBuf = AudioTrack.getMinBufferSize(outputSampleRate, channelMask, outputEncoding)
        check(minBuf > 0) { "AudioTrack does not support ${outputSampleRate}Hz x$outputChannels PCM" }
        val bytesPerSec = outputSampleRate * outputChannels * 2
        Logger.i("AudioTrack: buffer=${minBuf}B (~${minBuf * 1000 / bytesPerSec}ms)")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(outputEncoding)
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            // Minimum buffer for LOW LATENCY so audio lines up with the (immediately-rendered)
            // video. The upstream dedup jitter queue absorbs network jitter, so AudioTrack itself
            // only needs the floor. (If this underruns/crackles on load, raise toward minBuf*2.)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            audioTrack = track
            track.setVolume(volumeGain)
            return track
        } catch (e: Exception) {
            audioTrack = null
            runCatching { track.release() }
            throw e
        }
    }
}
