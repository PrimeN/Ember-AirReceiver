// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.miracast

import app.ember.airreceiver.airplay.rtsp.RtspRequest
import app.ember.airreceiver.airplay.rtsp.RtspRequestReader
import app.ember.airreceiver.airplay.rtsp.RtspResponse
import app.ember.airreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import android.view.Surface
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WfdRtspServer — The WFD RTSP control server a Miracast sender talks to.
 *
 * WHAT: Accepts one WFD RTSP client on port 7236, answers the WFD-specific RTSP methods
 * (OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN) and reports the
 * start and the end of a mirroring session.
 *
 * WHY: The WFD RTSP control channel is a protocol of its own — its methods and its sink
 * parameters differ from AirPlay's RTSP — and it had ended up in the same file as the
 * Wi-Fi Direct advertisement of [MiracastReceiver]. Advertisement and session control
 * are easier to read, review and change on their own once the server owns its file.
 *
 * HOW: [MiracastReceiver] creates one server, starts it on its coroutine scope as soon
 * as the P2P service is advertised and stops it when the receiver stops. The two
 * callbacks report the session transitions back to the receiver.
 *
 * Example:
 *   val server = WfdRtspServer(onSessionStarted = { connect() }, onSessionStopped = { disconnect() })
 *   server.start(scope)   // opens port 7236
 *   server.stop()         // closes the socket and reports the end of the session
 */
internal class WfdRtspServer(
    private val onSessionStarted: () -> Unit,
    private val onSessionStopped: () -> Unit,
    private val surface: () -> Surface? = { null },
    private val admitPlayback: () -> Boolean = { true },
    private val releasePlayback: () -> Unit = {},
    private val onPreparing: () -> Unit = {}
) {
    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = MAX_MESSAGE_BYTES
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var groupInterface: InetAddress? = null
    private var currentCSeq = 0
    @Volatile private var media: WfdMedia? = null

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing WFD RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
    }

    @Synchronized
    private fun claim(socket: Socket): Boolean {
        if (!running || activeClient != null) return false
        activeClient = socket
        return true
    }

    fun connectToSource(scope: CoroutineScope, peer: InetAddress, port: Int) {
        scope.launch(Dispatchers.IO) {
            val socket = Socket()
            if (!claim(socket)) { socket.close(); return@launch }
            try {
                socket.connect(InetSocketAddress(peer, port), 5000)
                handleClient(socket)
            } catch (e: Exception) {
                Logger.w("Mirror control connection unavailable")
            } finally {
                socket.close()
                synchronized(this@WfdRtspServer) { if (activeClient === socket) activeClient = null }
            }
        }
    }

    fun setGroupInterface(address: InetAddress?) {
        groupInterface = address
    }

    fun disconnect() {
        groupInterface = null
        runCatching { activeClient?.close() }
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            val listener = ServerSocket(MiracastReceiver.WFD_RTSP_PORT)
            serverSocket = listener
            if (!running) { listener.close(); return }
            Logger.i("WFD RTSP server listening on port ${MiracastReceiver.WFD_RTSP_PORT}")
            while (running && scope.isActive) {
                val client = listener.accept()
                if (client.localAddress != groupInterface || !claim(client)) {
                    sendServiceUnavailable(client)
                    client.close()
                    continue
                }
                handleClient(client)
            }
        } catch (e: Exception) {
            if (running) Logger.e("WFD RTSP server error", e)
        }
    }

    private fun handleClient(socket: Socket) {
        if (!admitPlayback()) {
            sendServiceUnavailable(socket)
            socket.close()
            synchronized(this) { if (activeClient === socket) activeClient = null }
            return
        }
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val receiver = WfdMedia(socket.inetAddress, surface,
                { if (activeClient === socket && !socket.isClosed) onSessionStarted() },
                { socket.close() })
            media = receiver
            var nextSequence = 1
            var setupSequence = -1
            var playSequence = -1
            var playing = false
            var inboundSetup = false
            var session = ""
            var presentation = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
            fun command(method: String, uri: String, extra: String = ""): Int {
                val sequence = nextSequence++
                output.write("$method $uri RTSP/1.0\r\nCSeq: $sequence\r\n${extra}\r\n".toByteArray())
                output.flush()
                return sequence
            }
            fun startMedia() {
                if (playing) return
                playing = true
                socket.soTimeout = 0
                onPreparing()
                receiver.start()
            }
            socket.soTimeout = 90000
            while (running && !socket.isClosed) {
                val request = requestReader.read(input) ?: break
                fun header(name: String) = request.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                currentCSeq = header("CSeq")?.toIntOrNull() ?: 0
                if (request.method == "RTSP/1.0") {
                    if (request.uri != "200") break
                    if (currentCSeq == setupSequence) {
                        session = header("Session")?.substringBefore(';') ?: break
                        val transport = header("Transport").orEmpty()
                        if (!transport.startsWith("RTP/AVP/UDP", true) && !transport.startsWith("RTP/AVP;", true)) break
                        playSequence = command("PLAY", presentation, "Session: $session\r\n")
                    } else if (currentCSeq == playSequence) startMedia()
                    continue
                }
                val response = if (request.method == "PLAY" && (!inboundSetup ||
                        header("Session")?.substringBefore(';') != WFD_SESSION_ID)) {
                    RtspResponse(455, "Session unavailable")
                } else routeRequest(request)
                sendResponse(output, response)
                when (request.method) {
                    "OPTIONS" -> command("OPTIONS", "*", "Require: org.wfa.wfd1.0\r\n")
                    "SETUP" -> inboundSetup = response.statusCode == 200
                    "PLAY" -> if (response.statusCode == 200) startMedia()
                    "TEARDOWN" -> break
                    "SET_PARAMETER" -> {
                        val params = request.body.lineSequence().associate {
                            it.substringBefore(':').trim() to it.substringAfter(':', "").trim()
                        }
                        params["wfd_presentation_URL"]?.substringBefore(' ')?.takeIf {
                            it.startsWith("rtsp://") && it.none(Char::isWhitespace)
                        }?.let { presentation = it }
                        when (params["wfd_trigger_method"]) {
                            "SETUP" -> setupSequence = command("SETUP", presentation,
                                "Transport: RTP/AVP/UDP;unicast;client_port=${receiver.port}\r\n")
                            "TEARDOWN" -> { command("TEARDOWN", presentation, "Session: $session\r\n"); break }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling WFD RTSP client", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.e("Error closing WFD RTSP client socket (non-fatal)", e)
            }
            media?.close()
            media = null
            releasePlayback()
            if (running) onSessionStopped()
            synchronized(this) { if (activeClient === socket) activeClient = null }
        }
    }

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("WFD RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")
            )
            "GET_PARAMETER" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Content-Type" to "text/parameters"),
                body = sinkParameters()
            )
            "SET_PARAMETER" -> RtspResponse(statusCode = 200, statusMessage = "OK")
            "SETUP" -> if (request.headers.entries.any { it.key.equals("Transport", true) &&
                    it.value.startsWith("RTP/AVP/UDP", true) } && media != null) {
                RtspResponse(200, "OK", mapOf("Session" to WFD_SESSION_ID,
                    "Transport" to "RTP/AVP/UDP;unicast;server_port=${media?.port}"))
            } else RtspResponse(461, "Unsupported Transport")
            "PLAY" -> if (media == null) RtspResponse(455, "Session unavailable")
                else RtspResponse(200, "OK", mapOf("Session" to WFD_SESSION_ID))
            "TEARDOWN" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )
            else -> RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
        }
    }

    private fun sinkParameters(): String =
        listOf(
            "wfd_audio_codecs: AAC 00000001 00",
            "wfd_video_formats: 00 00 02 10 0001FFFF 00000000 00000000 00 0000 0000 00 none none",
            "wfd_client_rtp_ports: RTP/AVP/UDP;unicast ${media?.port ?: 0} 0 mode=play",
            "wfd_content_protection: none",
            "wfd_display_edid: none",
            "wfd_coupled_sink: none",
            "wfd_connector_type: 05"
        ).joinToString(separator = "\r\n", postfix = "\r\n")

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        val sb = StringBuilder()
        sb.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: EmberAirReceiver/1.0\r\n")
        response.headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        if (response.body.isNotEmpty()) {
            sb.append("Content-Length: ${response.body.toByteArray(Charsets.UTF_8).size}\r\n")
        }
        sb.append("\r\n")
        sb.append(response.body)
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
        socket.outputStream.write(response.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 65536
        private const val WFD_SESSION_ID = "AirReceiverWfdSession"
    }
}
