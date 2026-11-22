package app.ember.airreceiver.airplay.rtsp

import app.ember.airreceiver.airplay.pairing.PlistCodec
import app.ember.airreceiver.airplay.playback.HlsPlaylistProxy
import app.ember.airreceiver.airplay.playback.MediaStartPosition
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Associates the sender's upgraded event socket and playlist replies with one active URL load. */
internal class SenderHlsTransport(
    private val onPlay: (String, MediaStartPosition) -> Unit,
    private val onStop: () -> Unit
) {
    private class Channel(val session: String, val socket: Socket) {
        val peer: InetAddress get() = socket.inetAddress
    }

    private class Reply(
        val channel: Channel,
        val url: String,
        val completion: CompletableFuture<ByteArray?> = CompletableFuture()
    )

    private var channel: Channel? = null
    private var proxy: HlsPlaylistProxy? = null
    private var generation = 0L
    private var nextRequest = 0L
    private val pending = mutableMapOf<Long, Reply>()

    @Synchronized
    fun matches(request: RtspRequest, peer: InetAddress): Boolean {
        val attached = channel ?: return false
        return attached.peer == peer && attached.session == sessionId(request)
    }

    @Synchronized
    fun isPlaying(): Boolean = proxy != null

    @Synchronized
    fun ownsPlayback(peer: InetAddress): Boolean = proxy != null && channel?.peer == peer

    @Synchronized
    fun attach(request: RtspRequest, socket: Socket): Boolean {
        val session = sessionId(request) ?: return false
        if (request.header("Upgrade")?.equals("PTTH/1.0", true) != true || channel != null) return false
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: PTTH/1.0\r\n\r\n".toByteArray())
        output.flush()
        channel = Channel(session, socket)
        return true
    }

    fun play(request: RtspRequest, peer: InetAddress?, url: String, offset: MediaStartPosition): Int? {
        val canRelay = peer != null && matches(request, peer) &&
            runCatching { URI(url).path.endsWith(".m3u8", true) }.getOrDefault(false)
        val playerUrl = synchronized(this) {
            if (canRelay) {
                val attached = channel ?: return null
                stopPlayback()
                val load = generation
                val relay = runCatching { HlsPlaylistProxy(url) { fetch(attached, load, it) } }.getOrNull()
                    ?: return 503
                proxy = relay
                relay.url
            } else {
                stopPlayback()
                return null
            }
        }
        onPlay(playerUrl, offset)
        return 200
    }

    fun handleAction(request: RtspRequest, peer: InetAddress): RtspResponse {
        if (!matches(request, peer) || request.bodyBytes.size > HlsPlaylistProxy.MAX_PLAYLIST_BYTES + 8192) {
            return RtspResponse(400, "Bad Request", protocol = "HTTP/1.1")
        }
        val plist = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull()
        val params = plist?.get("params") as? Map<*, *>
        val id = (params?.get("FCUP_Response_RequestID") as? Number)?.toLong()
        val url = params?.get("FCUP_Response_URL")
        val status = (params?.get("FCUP_Response_StatusCode") as? Number)?.toInt() ?: 200
        val data = params?.get("FCUP_Response_Data") as? ByteArray
        synchronized(this) {
            val reply = pending[id]
            if (plist?.get("type") != "unhandledURLResponse" ||
                reply == null || reply.channel !== channel || reply.url != url) {
                return RtspResponse(400, "Bad Request", protocol = "HTTP/1.1")
            }
            reply.completion.complete(data?.takeIf { status in 200..299 && it.size <= HlsPlaylistProxy.MAX_PLAYLIST_BYTES })
        }
        return RtspResponse(200, "OK", protocol = "HTTP/1.1")
    }

    private fun fetch(attached: Channel, load: Long, url: String): ByteArray? {
        val id: Long
        val reply: Reply
        synchronized(this) {
            if (channel !== attached || load != generation || pending.size >= 4) return null
            id = ++nextRequest
            reply = Reply(attached, url)
            pending[id] = reply
        }
        return try {
            val body = PlistCodec.encodeXml(mapOf(
                "type" to "unhandledURLRequest",
                "sessionID" to 1L,
                "request" to mapOf(
                    "sessionID" to 1L,
                    "FCUP_Response_RequestID" to id,
                    "FCUP_Response_URL" to url,
                    "FCUP_Response_ClientInfo" to 1L,
                    "FCUP_Response_ClientRef" to 40030004L,
                    "FCUP_Response_Headers" to mapOf("X-Playback-Session-Id" to attached.session)
                )
            ))
            synchronized(attached) {
                val output = attached.socket.getOutputStream()
                output.write(("POST /event HTTP/1.1\r\nX-Apple-Session-ID: ${attached.session}\r\n" +
                    "Content-Type: text/x-apple-plist+xml\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                output.write(body)
                output.flush()
            }
            reply.completion.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            synchronized(this) { pending.remove(id) }
        }
    }

    @Synchronized
    fun stopPlayback() {
        generation++
        pending.values.forEach { it.completion.complete(null) }
        pending.clear()
        proxy?.close()
        proxy = null
    }

    fun disconnected(socket: Socket) {
        val wasPlaying = synchronized(this) {
            if (channel?.socket !== socket) return
            val playing = proxy != null
            stopPlayback()
            channel = null
            playing
        }
        if (wasPlaying) onStop()
    }

    @Synchronized
    fun close() {
        stopPlayback()
        val socket = channel?.socket
        channel = null
        runCatching { socket?.close() }
    }

    private fun sessionId(request: RtspRequest): String? =
        request.header("X-Apple-Session-ID")?.takeIf { it.matches(SESSION_ID) }

    private fun RtspRequest.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    companion object {
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{16,80}")
    }
}
