// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.timing

import app.ember.airreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * AirPlayNtpClient — the receiver side of AirPlay 2 NTP timing.
 *
 * Unlike legacy AirPlay (where the sender probes the receiver), AirPlay 2 mirroring requires
 * the RECEIVER to actively poll the sender's timing port. macOS waits for this timing exchange
 * to begin before it will send the video stream SETUP, so without it the session stalls right
 * after the key exchange.
 *
 * We open a local UDP socket (its port is returned as `timingPort` in the SETUP response) and
 * periodically send a 32-byte NTP request. Matched replies map sender timestamps to
 * Android's monotonic clock without using the receiver's wall clock for playback.
 *
 * Example: `client.mirrorPresentationNanos(senderTimestamp, System.nanoTime())`.
 */
class AirPlayNtpClient(
    private val remoteAddress: InetAddress,
    private val remoteTimingPort: Int,
) {
    private val socket = DatagramSocket()      // OS-assigned local port
    @Volatile private var running = false

    @Volatile private var clock: TimingSample? = null

    internal fun mirrorPresentationNanos(timestamp: Long, nowNs: Long): Long? {
        val sample = clock ?: return null
        return mirrorPresentationNanos(sample, timestamp, nowNs)
    }

    /** Local UDP port to advertise to macOS as the receiver's timingPort. */
    val localPort: Int get() = socket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        socket.soTimeout = RECV_TIMEOUT_MS
        scope.launch(Dispatchers.IO) { loop() }
        Logger.i("NTP client → [$remoteAddress]:$remoteTimingPort, local timing port $localPort")
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun loop() {
        // request: [0]=0x80 (RTP), [1]=0xd2 (timing request), [2-3]=seq, [24-31]=send NTP time.
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte()
        request[3] = 0x07
        val response = ByteArray(128)
        var first = true
        var rxCount = 0
        while (running) {
            try {
                putNtpTimestamp(request, 24, System.currentTimeMillis())
                val sentNs = System.nanoTime()
                socket.send(DatagramPacket(request, request.size, remoteAddress, remoteTimingPort))
                if (first) { Logger.i("NTP: first timing request sent to macOS"); first = false }
                try {
                    val rx = DatagramPacket(response, response.size)
                    socket.receive(rx)
                    val receivedNs = System.nanoTime()
                    val matchesPeer = rx.address == remoteAddress && rx.port == remoteTimingPort && rx.length >= 32
                    val sample = if (matchesPeer) timingSample(request, response, sentNs, receivedNs) else null
                    val previous = clock
                    if (sample != null && (previous == null ||
                        receivedNs - previous.receivedNs > BEST_SAMPLE_AGE_NS ||
                        sample.roundTripNs <= previous.roundTripNs)) {
                        clock = sample
                    }
                    if (matchesPeer && rxCount < 4) {
                        Logger.i("NTP RX[$rxCount] ${rx.length}B type=0x${(response[1].toInt() and 0xFF).toString(16)}: " +
                            (0 until minOf(rx.length, 32)).joinToString(" ") { "%02x".format(response[it]) })
                        rxCount++
                    }
                } catch (_: Exception) { /* SO_RCVTIMEO — fine, retry next tick */ }
            } catch (e: Exception) {
                if (running) Logger.e("NTP client send error", e)
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** Writes a 64-bit NTP timestamp (seconds since 1900 + 32-bit fraction) big-endian. */
    private fun putNtpTimestamp(buf: ByteArray, off: Int, epochMillis: Long) {
        val seconds = epochMillis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (epochMillis % 1000) * (1L shl 32) / 1000
        writeUint32(buf, off, seconds)
        writeUint32(buf, off + 4, fraction)
    }

    private fun writeUint32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    /** Paired sender and receiver midpoints, recorded at the end of one timing exchange. */
    internal data class TimingSample(
        val senderTimestamp: Long,
        val localMidpointNs: Long,
        val receivedNs: Long,
        val roundTripNs: Long
    )

    companion object {
        private const val MAX_SAMPLE_AGE_NS = 10_000_000_000L
        private const val BEST_SAMPLE_AGE_NS = 6_000_000_000L
        private const val VIDEO_PLAYOUT_DELAY_NS = 20_000_000L
        private const val MAX_ROUND_TRIP_NS = 150_000_000L

        internal fun mirrorPresentationNanos(sample: TimingSample, timestamp: Long, nowNs: Long): Long? {
            if (timestamp == 0L || nowNs - sample.receivedNs > MAX_SAMPLE_AGE_NS) return null
            val rawDelta = timestamp - sample.senderTimestamp
            val standard = fixedPointDeltaNanos(rawDelta)
            // Mirror headers also occur with the Unix epoch, while probes use NTP timestamps.
            val unixEpoch = fixedPointDeltaNanos(rawDelta + (NTP_EPOCH_OFFSET shl 32))
            val delta = if (abs(standard) <= abs(unixEpoch)) standard else unixEpoch
            if (abs(delta) > MAX_SAMPLE_AGE_NS) return null
            return sample.localMidpointNs + delta + VIDEO_PLAYOUT_DELAY_NS
        }

        internal fun fixedPointDeltaNanos(delta: Long): Long =
            (delta shr 32) * 1_000_000_000L + ((delta and 0xFFFF_FFFFL) * 1_000_000_000L ushr 32)

        internal fun timingSample(
            request: ByteArray, response: ByteArray, sentNs: Long, receivedNs: Long
        ): TimingSample? {
            if (request.size < 32 || response.size < 32) return null
            val reply = ByteBuffer.wrap(response)
            if (reply.get(1).toInt() and 0xFF != 0xD3 ||
                reply.getLong(8) != ByteBuffer.wrap(request).getLong(24)) return null
            val senderReceived = reply.getLong(16)
            val senderSent = reply.getLong(24)
            if (senderReceived == 0L || senderSent == 0L) return null
            val senderInterval = senderSent - senderReceived
            val elapsedNs = receivedNs - sentNs
            val delayNs = elapsedNs - fixedPointDeltaNanos(senderInterval)
            if (senderInterval < 0 || elapsedNs !in 0..MAX_ROUND_TRIP_NS || delayNs < 0) return null
            return TimingSample(senderReceived + senderInterval / 2, sentNs + elapsedNs / 2, receivedNs, delayNs)
        }

        private const val NTP_EPOCH_OFFSET = 2208988800L   // seconds between 1900 and 1970
        private const val POLL_INTERVAL_MS = 2000L
        private const val RECV_TIMEOUT_MS = 1000
    }
}
