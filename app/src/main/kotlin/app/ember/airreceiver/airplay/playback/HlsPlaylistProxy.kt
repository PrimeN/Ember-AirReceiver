package app.ember.airreceiver.airplay.playback

import app.ember.airreceiver.airplay.rtsp.RtspRequest
import app.ember.airreceiver.airplay.rtsp.RtspRequestReader
import app.ember.airreceiver.util.FetchableUrlPolicy
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Session-local playlist and segment serving for unprotected sender-mediated HLS. */
internal class HlsPlaylistProxy(
    root: String,
    private val fetchPlaylist: (String) -> ByteArray?
) : Closeable {
    private data class Resource(val uri: String, val playlist: Boolean)

    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val pool = ThreadPoolExecutor(0, 4, 5, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
        Thread(task, "Ember playlist request").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val downloads = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val token = UUID.randomUUID().toString()
    private val baseUrl = "http://127.0.0.1:${listener.localPort}/$token/"
    private val entries = LinkedHashMap<String, Resource>(256, 0.75f, true)
    private val pathByResource = HashMap<Resource, String>()
    private var nextPath = 0L
    val url = expose(root, true)
    private val reader = RtspRequestReader(8192, 0)

    init {
        Thread(::accept, "Ember playlist listener").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun expose(uri: String, playlist: Boolean): String {
        val resource = Resource(uri, playlist)
        val existing = pathByResource[resource]
        if (existing != null) {
            entries[existing] = resource
            return baseUrl + existing
        }
        if (entries.size >= 2048) {
            val oldest = entries.entries.iterator()
            pathByResource.remove(oldest.next().value)
            oldest.remove()
        }
        val path = (++nextPath).toString() + if (playlist) ".m3u8" else ".media"
        entries[path] = resource
        pathByResource[resource] = path
        return baseUrl + path
    }

    private fun accept() {
        while (!listener.isClosed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: break
            sockets.add(socket)
            if (listener.isClosed) {
                sockets.remove(socket)
                socket.close()
                break
            }
            try {
                pool.execute {
                    try {
                        socket.use(::serve)
                    } catch (_: Exception) {
                        // The player may cancel a request while replacing the stream.
                    } finally {
                        sockets.remove(socket)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                sockets.remove(socket)
                socket.close()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 4_000
        val request = reader.read(socket.getInputStream()) ?: return
        if (request.method != "GET") return respond(socket, 405)
        val prefix = "/$token/"
        if (!request.uri.startsWith(prefix)) return respond(socket, 404)
        val path = request.uri.substringBefore('?').removePrefix(prefix)
        val resource = synchronized(this) { entries[path] } ?: return respond(socket, 404)
        if (resource.playlist) {
            val bytes = fetchPlaylist(resource.uri) ?: return respond(socket, 502)
            val rewritten = runCatching {
                rewrite(bytes, resource.uri, ::expose)
            }.getOrNull() ?: return respond(socket, 422)
            writeHeaders(socket, 200, "application/vnd.apple.mpegurl", rewritten.size.toLong())
            socket.getOutputStream().write(rewritten)
        } else {
            serveMedia(socket, request, resource.uri)
        }
    }

    private fun serveMedia(socket: Socket, request: RtspRequest, uri: String) {
        if (!FetchableUrlPolicy.isFetchable(uri)) return respond(socket, 422)
        val connection = URI(uri).toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        downloads.add(connection)
        try {
            if (listener.isClosed) return
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            val range = request.headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
            if (range != null) {
                if (!range.matches(Regex("bytes=[0-9]{1,18}-[0-9]{0,18}"))) return respond(socket, 416)
                connection.setRequestProperty("Range", range)
            }
            val code = connection.responseCode
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            if ((code != 200 && code != 206) || length > MAX_MEDIA_BYTES) return respond(socket, 502)
            val contentRange = connection.getHeaderField("Content-Range")
                ?.takeIf { it.matches(Regex("bytes [0-9]{1,18}-[0-9]{1,18}/([0-9]{1,18}|\\*)")) }
            if (code == 206 && contentRange == null) return respond(socket, 502)
            connection.inputStream.use { input ->
                writeHeaders(socket, code, "application/octet-stream", length, contentRange)
                val output = socket.getOutputStream()
                val buffer = ByteArray(16 * 1024)
                var remaining = MAX_MEDIA_BYTES
                while (remaining > 0 && !listener.isClosed) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        } finally {
            downloads.remove(connection)
            connection.disconnect()
        }
    }

    private fun respond(socket: Socket, code: Int) = writeHeaders(socket, code, "text/plain", 0)

    private fun writeHeaders(socket: Socket, code: Int, type: String, size: Long, range: String? = null) {
        val status = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            422 -> "Unprocessable Content"
            else -> "Bad Gateway"
        }
        val headers = buildString {
            append("HTTP/1.1 $code $status\r\nConnection: close\r\nContent-Type: $type\r\n")
            if (size >= 0) append("Content-Length: $size\r\n")
            if (range != null) append("Content-Range: $range\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        downloads.forEach { it.disconnect() }
        pool.shutdownNow()
    }

    companion object {
        private const val MAX_MEDIA_BYTES = 32L * 1024 * 1024
        internal const val MAX_PLAYLIST_BYTES = 256 * 1024
        private val uriAttribute = Regex("URI=\"([^\"\r\n]+)\"")

        internal fun rewrite(data: ByteArray, from: String, expose: (String, Boolean) -> String): ByteArray {
            require(data.size in 1..MAX_PLAYLIST_BYTES)
            val lines = data.decodeToString().lineSequence().iterator()
            require(lines.hasNext() && lines.next().trim() == "#EXTM3U")
            val base = URI(from)
            fun resolve(text: String): String {
                val uri = base.resolve(text).toString()
                require(FetchableUrlPolicy.isFetchable(uri))
                return uri
            }
            var variant = false
            val result = StringBuilder("#EXTM3U\n")
            while (lines.hasNext()) {
                val line = lines.next().trim()
                require(!line.startsWith("#EXT-X-SESSION-KEY:"))
                require(!line.startsWith("#EXT-X-DEFINE:"))
                if (line.startsWith("#EXT-X-KEY:")) require(line == "#EXT-X-KEY:METHOD=NONE")
                if (line.startsWith("#EXT-X-STREAM-INF:")) variant = true
                if (line.isNotEmpty() && !line.startsWith('#')) {
                    val source = resolve(line)
                    result.append(expose(source, variant || URI(source).path.endsWith(".m3u8", true)))
                    variant = false
                } else {
                    val playlist = line.startsWith("#EXT-X-MEDIA:") ||
                        line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                        line.startsWith("#EXT-X-RENDITION-REPORT:")
                    result.append(uriAttribute.replace(line) { "URI=\"${expose(resolve(it.groupValues[1]), playlist)}\"" })
                }
                result.append('\n')
                require(result.length <= MAX_PLAYLIST_BYTES * 2)
            }
            return result.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
