// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.rtsp

import app.ember.airreceiver.airplay.pairing.FairPlay
import app.ember.airreceiver.airplay.pairing.PairingKeys
import app.ember.airreceiver.airplay.pairing.PairingSession
import app.ember.airreceiver.airplay.playback.PhotoHandler
import app.ember.airreceiver.util.Logger
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/**
 * RtspServer — owns the AirPlay RTSP control socket.
 *
 * WHAT: Binds port 7000, accepts one sender at a time, reads requests with [RtspRequestReader],
 * hands them to the [RtspRequestRouter] and writes the answers back to the socket. After a
 * successful RECORD it can switch the same socket over to interleaved RTP.
 *
 * WHY: Socket handling — binding, the single-client rule, partial reads, binary-safe response
 * framing — is a concern of its own and has nothing to do with the AirPlay verbs. Separating it
 * keeps the protocol handlers testable without a real network socket.
 *
 * Example:
 *   val server = RtspServer(context, sessionState, router, onStreamingStopped = { })
 *   scope.launch(Dispatchers.IO) { server.runServer(this) }   // accepts AirPlay senders
 */
internal class RtspServer(
    private val context: android.content.Context,
    private val newControl: (Socket, RtspSessionState) -> Connection,
    private val senderHls: SenderHlsTransport,
    private val onStreamingStopped: () -> Unit,
    private val onPlaybackReplaced: () -> Unit,
    private val admitPlayback: () -> Boolean,
    private val allowHandoff: () -> Boolean
) {
    internal class Connection(val route: (RtspRequest) -> RtspResponse, private val onClose: () -> Unit) {
        fun close() = onClose()
    }

    internal var serverSocket: ServerSocket? = null
    @Volatile internal var activeClient: Socket? = null
    @Volatile private var playbackClient: Socket? = null
    @Volatile internal var running = false
    @Volatile var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    private val controlLock = Any()
    private val accepted = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionSlots = Semaphore(5)

    internal fun closeAcceptedSockets() {
        accepted.forEach { runCatching { it.close() } }
    }

    internal fun ownsPlayback(socket: Socket) = playbackClient === socket && !socket.isClosed

    internal fun finishPlayback(socket: Socket) = synchronized(controlLock) {
        if (playbackClient === socket) {
            playbackClient = null
            onStreamingStopped()
        }
    }

    internal fun claimPlayback(socket: Socket): Boolean = synchronized(controlLock) {
        if (!running || socket.isClosed) return@synchronized false
        if (playbackClient === socket) return@synchronized true
        val previous = activeClient
        if (previous != null && previous !== socket && !allowHandoff()) return@synchronized false
        if (!admitPlayback()) return@synchronized false
        if (previous != null && previous !== socket) {
            runCatching { previous.close() }
            if (playbackClient === previous) {
                playbackClient = null
                onPlaybackReplaced()
            }
        }
        activeClient = socket
        playbackClient = socket
        true
    }

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES
    )

    /**
     * Binds the RTSP port with SO_REUSEADDR, retrying briefly if a just-stopped instance hasn't
     * released it yet. A quick service stop→start (the activity being destroyed and relaunched)
     * could otherwise fail with EADDRINUSE, leaving Ember AirReceiver advertising over mDNS while port 7000
     * was dead — macOS would discover it and try to mirror but nothing could connect ("casting but
     * nothing shows"). SO_REUSEADDR handles TIME_WAIT; the retry covers the close/rebind race.
     */
    private fun bindRtspSocket(): ServerSocket {
        var lastError: java.io.IOException? = null
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (!running) throw java.io.IOException("RTSP server stopped before bind")
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    // Bind explicitly to the IPv4 wildcard: InetSocketAddress(port) resolves to
                    // an IPv6 wildcard on Android, which some devices' network stacks don't
                    // dual-stack onto the LAN's IPv4 interface — IPv4 senders (most phones on a
                    // typical home LAN) then get their SYN silently dropped instead of accepted.
                    bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), RTSP_PORT))
                }
            } catch (e: java.io.IOException) {
                lastError = e
                Logger.w("RTSP port $RTSP_PORT busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { throw e }
            }
        }
        throw lastError ?: java.io.IOException("RTSP bind to $RTSP_PORT failed")
    }

    internal fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = bindRtspSocket()
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                val clientSocket = serverSocket!!.accept()
                if (!connectionSlots.tryAcquire()) {
                    clientSocket.close()
                    continue
                }
                accepted.add(clientSocket)
                scope.launch(Dispatchers.IO) {
                    try {
                        clientSocket.use { if (running) dispatchAccepted(it, scope) }
                    } catch (e: java.io.IOException) {
                        if (running) Logger.d("AirPlay connection closed")
                    } finally {
                        accepted.remove(clientSocket)
                        connectionSlots.release()
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    private fun dispatchAccepted(socket: Socket, scope: CoroutineScope) {
        socket.soTimeout = 8_000
        val first = requestReader.read(socket.getInputStream()) ?: return
        if (first.method == "POST" && first.uri == "/reverse") {
            if (!senderHls.attach(first, socket)) {
                sendResponse(socket.getOutputStream(), RtspResponse(409, "Conflict", protocol = "HTTP/1.1"))
                return
            }
            val claimTimeout = scope.launch {
                delay(30_000)
                senderHls.expireUnclaimed(socket)
            }
            try {
                socket.soTimeout = 0
                while (running) {
                    val event = requestReader.read(socket.getInputStream()) ?: break
                    if (event.method == "POST" && event.uri == "/action") {
                        val response = senderHls.handleAction(event, socket.inetAddress)
                        senderHls.replyOnReverse(socket) { output -> sendResponse(output, response) }
                    }
                }
            } finally {
                claimTimeout.cancel()
                synchronized(controlLock) { senderHls.disconnected(socket) }
            }
            return
        }
        if (first.method == "POST" && first.uri == "/action") {
            sendResponse(socket.getOutputStream(), senderHls.handleAction(first, socket.inetAddress))
            return
        }
        if (senderHls.isPlaying() && !senderHls.matches(first, socket.inetAddress)) {
            sendServiceUnavailable(socket)
            return
        }
        val permitted = synchronized(controlLock) {
            if (!running) false
            else if (activeClient == null) {
                activeClient = socket
                if (senderHls.ownsPlayback(socket.inetAddress)) playbackClient = socket
                true
            } else allowHandoff()
        }
        if (!permitted) {
            sendServiceUnavailable(socket)
            return
        }
        handleClient(socket, first)
    }

    private fun handleClient(socket: Socket, first: RtspRequest) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()
        val state = RtspSessionState().apply {
            pairingSession = PairingSession(PairingKeys.get(context))
            fairPlay = FairPlay()
            currentRemoteAddress = socket.inetAddress
        }
        var connection: Connection? = null
        try {
            val control = newControl(socket, state)
            connection = control
            var next: RtspRequest? = first
            while (running && !socket.isClosed && next != null) {
                val request = next
                val response = synchronized(controlLock) {
                    if (socket.isClosed) return
                    control.route(request)
                }
                sendResponse(outputStream, response, request.headers["CSeq"]?.toIntOrNull() ?: 0)
                if (request.method == "RECORD" && response.statusCode == 200 && !state.isMirrorSession &&
                    state.currentSession?.hasVideo == true && ownsPlayback(socket)) {
                    socket.soTimeout = 0
                    RtpInterleaved.readLoop(
                        inputStream = inputStream,
                        onVideoNalUnit = { unit, pts -> if (ownsPlayback(socket)) onVideoNalUnit?.invoke(unit, pts) },
                        onStreamEnded = { Logger.i("RTP stream ended") }
                    )
                    break
                }
                // A waiting connection cannot occupy a negotiation slot indefinitely.
                socket.soTimeout = if (activeClient === socket) 0 else CANDIDATE_TIMEOUT_MS
                next = requestReader.read(inputStream)
            }
        } catch (e: Exception) {
            if (running && !socket.isClosed) Logger.e("Error handling RTSP client", e)
        } finally {
            runCatching { socket.close() }
            synchronized(controlLock) {
                connection?.close()
                if (activeClient === socket) {
                    if (!senderHls.ownsPlayback(socket.inetAddress)) finishPlayback(socket)
                    else if (playbackClient === socket) playbackClient = null
                    activeClient = null
                }
            }
        }
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse, currentCSeq: Int = 0) {
        // Binary-safe: build the header block as ASCII, then write the raw body bytes.
        // Content-Length must be the BYTE length (not String.length) so binary plists,
        // FairPlay payloads, and encrypted bodies are framed correctly.
        val wire = response.wireBody()
        val head = StringBuilder()
        head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        if (response.protocol.startsWith("RTSP")) {
            head.append("CSeq: $currentCSeq\r\n")
        }
        head.append("Server: AirTunes/220.68\r\n")
        response.contentType?.let { head.append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) ->
            head.append("$key: $value\r\n")
        }
        if (wire.isNotEmpty()) {
            head.append("Content-Length: ${wire.size}\r\n")
        }
        head.append("\r\n")
        outputStream.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (wire.isNotEmpty()) {
            outputStream.write(wire)
        }
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        private const val CANDIDATE_TIMEOUT_MS = 30_000
        private const val RTSP_PORT = 7000

        private const val BIND_MAX_ATTEMPTS = 12      // ~3s total — covers a quick stop→start restart
        private const val BIND_RETRY_MS = 250L
        // iOS/iPadOS mirror-stream SETUP requests carry a large binary plist
        // (observed ~77 KB from iPhone17,1 on iOS 26) that exceeded the previous
        // 64 KB cap, so the reader rejected the video stream SETUP and the sender
        // tore the session down right after audio started. 1 MB gives ample head-
        // room for future senders while staying trivially safe on a LAN.
        private const val MAX_MESSAGE_BYTES = 1024 * 1024
    }
}
