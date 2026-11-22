// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.audio

import java.net.SocketAddress
import java.util.concurrent.ArrayBlockingQueue

/**
 * AudioReorderBuffer — Puts arriving audio RTP packets back into order and repairs losses.
 *
 * WHAT: Suppresses duplicate packets, holds packets in a small sequence-keyed reorder
 * buffer, hands every now-contiguous packet to the playback queue in order and asks the
 * sender to resend packets that went missing.
 *
 * WHY: macOS's `redundantAudio` (each packet sent 2–3×) covers most loss, but a burst
 * that drops all copies leaves a gap. Decoding every copy instead feeds the AAC decoder
 * duplicate frames and pushes 2–3× real-time data into the AudioTrack — the buffer
 * overflows, chunks get dropped, and playback both glitches and lags the video. This class
 * keeps that logic in one place, away from the socket and the decoder, so the sequencing
 * rules can be read (and changed) without touching the rest of the audio path.
 *
 * HOW: The receive thread hands every raw packet to [handleRtpPacket]; the control thread
 * reports the sender's address via [senderCtrlAddr] and splices resent packets back in via
 * the same entry point. Released packets are handed to the playback queue.
 *
 * Example:
 *   val buffer = AudioReorderBuffer(frameQueue, controlChannel)
 *   buffer.senderCtrlAddr = senderAddress
 *   buffer.handleRtpPacket(datagram.data, 0, datagram.length)
 */
internal class RealtimeAudioFrame(val payload: ByteArray, val rtpTime: Int)

internal class AudioReorderBuffer(
    /** Bounded jitter buffer between the receive thread and the playback thread. */
    private val frameQueue: ArrayBlockingQueue<RealtimeAudioFrame>,
    /** Sink for the RAOP resend requests this buffer produces. */
    private val controlChannel: RaopControlChannel,
    private val nanoTime: () -> Long = System::nanoTime
) {

    // ─── Reorder buffer + packet-loss retransmit ─────────────────────────────
    // macOS's `redundantAudio` (each packet sent 2–3×) covers most loss, but a burst that drops
    // all copies leaves a gap. We hold packets in a small seq-keyed reorder buffer and, on a gap,
    // ask the sender to resend the missing range (RAOP control type 0x55) — the resent packet comes
    // back on the control socket (type 0x56) and fills the hole. Common case (in-order) releases
    // immediately. A gap is bounded by sequence distance and a short monotonic deadline.
    // Both the data and control receive threads use reorderLock.
    private val reorderLock = Any()
    private val sendLock = Any()                       // serialises control-socket resend sends
    private val reorder = HashMap<Int, RealtimeAudioFrame>()   // seq → decrypted-pending RTP payload
    private var nextSeq = -1                            // next seq to release in order (-1 = uninit)
    private var maxSeq = -1                             // highest seq seen (for gap detection)
    private var gapDeadline: Long? = null
    private var resendCtr = 0                           // sequence counter for our resend requests

    /** The sender's control address — learned from its first control packet. */
    @Volatile var senderCtrlAddr: SocketAddress? = null

    /** Number of packets that were dropped as duplicates. */
    @Volatile var dupCount = 0

    /** Number of packets that were dropped because the playback queue was full. */
    @Volatile var qDropCount = 0

    /** Number of resend requests we sent to the sender. */
    @Volatile var resendReqCount = 0

    /** Number of resent packets the sender sent back to us. */
    @Volatile var resendFillCount = 0

    // RTP duplicate suppression. macOS sends each realtime-audio packet 2–3× for redundancy
    // (same 16-bit sequence number). Decoding every copy feeds the AAC decoder duplicate frames
    // and pushes 2–3× real-time data into AudioTrack — the buffer overflows, chunks get dropped,
    // and playback both glitches and lags video. We process each sequence number exactly once,
    // remembering a sliding window of recent seqs (well under the 65536 wrap and ~11 s deep).
    private val seenSeqs = java.util.ArrayDeque<Int>()
    private val seenSeqSet = HashSet<Int>()

    /**
     * Parses one RTP audio packet (from the data socket or a resend reply) and routes it through the
     * reorder buffer. [src] may be a reused receive buffer, so the payload is copied out before any
     * cross-thread handoff. Thread-safe: the reorder buffer + dedup are accessed under [reorderLock].
     */
    fun handleRtpPacket(src: ByteArray, offset: Int, length: Int) {
        if (length <= RTP_HEADER) return
        val seq = rtpSequence(src, offset)
        // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted audio payload (copied out of src).
        val timestamp = java.nio.ByteBuffer.wrap(src, offset + 4, 4).int
        val frame = RealtimeAudioFrame(src.copyOfRange(offset + RTP_HEADER, offset + length), timestamp)
        val resend = synchronized(reorderLock) {
            releaseExpiredGap()
            if (isDuplicateSeq(seq)) { dupCount++; return }
            enqueueInOrder(seq, frame)
        }
        // Send the resend request OUTSIDE the reorder lock — never hold it across socket I/O.
        resend?.let { requestResend(it[0], it[1]) }
    }

    /**
     * Inserts [seq]/[payload] into the reorder buffer and releases all now-contiguous packets to the
     * player in order. Returns the [startSeq, count] of a missing range to resend (or null). Under [reorderLock].
     */
    private fun enqueueInOrder(seq: Int, payload: RealtimeAudioFrame): IntArray? {
        if (nextSeq < 0) { nextSeq = seq; maxSeq = seq }      // first packet anchors the stream
        // Ignore packets older than what we've already released (a late resend we gave up on).
        if (seqDiff(seq, nextSeq) < 0) return null
        reorder[seq] = payload
        // New forward gap → ask the sender to resend the packets between the old high-water mark and here.
        val resend = if (maxSeq >= 0 && seqDiff(seq, maxSeq) > 1)
            intArrayOf((maxSeq + 1) and 0xFFFF, seqDiff(seq, maxSeq) - 1) else null
        if (seqDiff(seq, maxSeq) > 0) maxSeq = seq
        releaseContiguous()
        // If a hole stays unfilled and the buffer runs too far ahead, skip past it so playback never
        // stalls (a brief glitch beats indefinite silence).
        if (reorder.isNotEmpty() && seqDiff(maxSeq, nextSeq) > MAX_REORDER_HOLD) {
            while (seqDiff(maxSeq, nextSeq) > MAX_REORDER_HOLD && !reorder.containsKey(nextSeq)) {
                nextSeq = (nextSeq + 1) and 0xFFFF
            }
            releaseContiguous()
        }
        gapDeadline = if (reorder.isEmpty()) null else gapDeadline ?: (nanoTime() + GAP_WAIT_NS)
        return resend
    }

    /** Hands every packet contiguous from [nextSeq] to the player, advancing [nextSeq]. Under [reorderLock]. */
    private fun releaseContiguous() {
        while (true) {
            val p = reorder.remove(nextSeq) ?: break
            if (!frameQueue.offer(p)) {
                // Preserve the playback reset even when a burst fills the new queue.
                val oldest = frameQueue.firstOrNull { it !== FLUSH }
                if (oldest != null && frameQueue.remove(oldest)) qDropCount++
                if (!frameQueue.offer(p)) qDropCount++
            }
            nextSeq = (nextSeq + 1) and 0xFFFF
        }
    }

    /**
     * Sends a RAOP resend request (control type 0x55) for [count] packets starting at [startSeq].
     * Guarded by [sendLock] (separate from [reorderLock]) so two receive threads don't send + bump
     * [resendCtr] concurrently. Runs outside the reorder lock — never blocks the decode path on I/O.
     */
    private fun requestResend(startSeq: Int, count: Int) {
        if (count <= 0 || count > MAX_RESEND_RANGE) return
        synchronized(sendLock) {
            val addr = senderCtrlAddr ?: return    // unknown until the sender's first control packet
            val req = ByteArray(8)
            req[0] = 0x80.toByte()
            req[1] = (RTP_TYPE_RESEND_REQUEST or 0x80).toByte()   // marker bit set, per RAOP
            req[2] = (resendCtr ushr 8).toByte(); req[3] = resendCtr.toByte()
            req[4] = (startSeq ushr 8).toByte();   req[5] = startSeq.toByte()
            req[6] = (count ushr 8).toByte();      req[7] = count.toByte()
            resendCtr = (resendCtr + 1) and 0xFFFF
            resendReqCount++
            runCatching { controlChannel.send(req, addr) }
        }
    }

    /** Signed 16-bit sequence distance a − b in (−32768, 32767], so wraparound compares correctly. */
    private fun seqDiff(a: Int, b: Int): Int = (((a - b) and 0xFFFF) xor 0x8000) - 0x8000

    /** True if this RTP sequence was already processed (a redundant retransmission). */
    private fun isDuplicateSeq(seq: Int): Boolean {
        if (!seenSeqSet.add(seq)) return true          // add() returns false when already present
        seenSeqs.addLast(seq)
        if (seenSeqs.size > SEQ_WINDOW) seenSeqSet.remove(seenSeqs.removeFirst())
        return false
    }

    /** Advances a gap even if the sender has stopped transmitting new packets. */
    fun expireGap() = synchronized(reorderLock) { releaseExpiredGap() }

    private fun releaseExpiredGap() {
        val deadline = gapDeadline ?: return
        if (nanoTime() - deadline < 0) return
        // Visit the packets we have, even when a timestamp discontinuity left a large gap.
        for (seq in reorder.keys.sortedBy { seqDiff(it, nextSeq) }) {
            if (!reorder.containsKey(seq)) continue
            nextSeq = seq
            releaseContiguous()
        }
        gapDeadline = null
    }

    /** Starts a new RTP sequence and orders the decoder reset before any subsequent payload. */
    fun flush(firstSequence: Int?) = synchronized(reorderLock) {
        reset(firstSequence)
        frameQueue.add(FLUSH)
    }

    fun clear() = synchronized(reorderLock) { reset(null) }

    private fun reset(firstSequence: Int?) {
        reorder.clear()
        frameQueue.clear()
        seenSeqs.clear()
        seenSeqSet.clear()
        nextSeq = firstSequence ?: -1
        maxSeq = firstSequence?.let { (it - 1) and 0xFFFF } ?: -1
        gapDeadline = null
    }

    companion object {
        // Payloads are nonempty, so this identity can be reserved for a playback command.
        internal val FLUSH = RealtimeAudioFrame(ByteArray(0), 0)
        private const val GAP_WAIT_NS = 60_000_000L

        internal fun rtpSequence(packet: ByteArray, offset: Int = 0): Int =
            ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)

        /** Size of the RAOP RTP header in front of the encrypted audio payload. */
        internal const val RTP_HEADER = 12

        // RAOP control-channel RTP payload types for packet-loss recovery.
        private const val RTP_TYPE_RESEND_REQUEST = 0x55   // we → sender: "resend these seqs"

        // Max packets to hold while waiting for a gap to fill before skipping it (bounds the worst-case
        // added latency to ~this many frames; in-order traffic adds zero). ~32 ≈ 0.25–0.35 s.
        private const val MAX_REORDER_HOLD = 32

        // Don't ask for an absurd resend range (a huge gap = a real stall, not a few lost packets).
        private const val MAX_RESEND_RANGE = 128

        // Sliding window of recently-played RTP sequence numbers for duplicate suppression.
        // ~11 s at 92 packets/s — far longer than any retransmit gap, far shorter than the
        // 65536-packet (~12 min) sequence-number wrap, so no false positives from wraparound.
        private const val SEQ_WINDOW = 1024
    }
}
