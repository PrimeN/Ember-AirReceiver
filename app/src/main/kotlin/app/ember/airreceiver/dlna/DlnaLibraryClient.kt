package app.ember.airreceiver.dlna

import app.ember.airreceiver.dlna.soap.SecureSoapXml
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import org.w3c.dom.Element

/** Discovers local MediaServers and browses their ContentDirectory in bounded pages. */
internal class DlnaLibraryClient {
    data class Server(val title: String, val control: URI, val service: String)
    data class Entry(val title: String, val container: String? = null, val uri: String? = null, val mime: String? = null)
    data class Page(val entries: List<Entry>, val nextOffset: Int?)

    fun discover(): List<Server> {
        val request = (
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val candidates = linkedSetOf<URI>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
            val until = System.nanoTime() + 2_400_000_000L
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            while (!Thread.currentThread().isInterrupted && System.nanoTime() < until && candidates.size < 8) {
                try {
                    packet.length = buffer.size
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val sender = packet.address
                if (!sender.isSiteLocalAddress) continue
                val reply = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                if (!reply.startsWith("HTTP/1.1 200 ")) continue
                val location = reply.lineSequence().firstOrNull { it.substringBefore(':').equals("location", true) }
                    ?.substringAfter(':')?.trim() ?: continue
                sameHost(location, sender.hostAddress.orEmpty())?.let { candidates.add(it) }
            }
        }
        return candidates.mapNotNull { location ->
            if (Thread.currentThread().isInterrupted) return emptyList()
            runCatching { describe(location) }.getOrNull()
        }.distinctBy { it.control }.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
    }

    fun browse(server: Server, id: String, offset: Int): Page {
        require(offset in 0..100_000 && id.length <= 1024)
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Browse xmlns:u="${server.service}"><ObjectID>${escape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>24</RequestedCount><SortCriteria/></u:Browse></s:Body></s:Envelope>"""
        val reply = SecureSoapXml.parse(fetchXml(server.control, server.service, body))
        val result = SecureSoapXml.directChildText(reply, "BrowseResponse")
        if (result["Result"].isNullOrBlank()) return Page(emptyList(), null)
        val items = SecureSoapXml.parse(result.getValue("Result")).documentElement.children()
        val entries = items.take(24).mapNotNull { element ->
            val children = element.children()
            val title = children.firstOrNull { it.localName == "title" }?.textContent?.trim()?.take(200)
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (element.localName == "container") {
                val container = element.getAttribute("id").takeIf { it.isNotEmpty() && it.length <= 1024 }
                    ?: return@mapNotNull null
                Entry(title, container = container)
            } else if (element.localName == "item") {
                children.asSequence().filter { it.localName == "res" }.mapNotNull resourceEntry@{ resource ->
                    val value = resource.textContent.trim().takeIf { it.isNotEmpty() } ?: return@resourceEntry null
                    val candidate = runCatching { server.control.resolve(value).toString() }.getOrNull()
                        ?: return@resourceEntry null
                    val url = sameHost(candidate, server.control.host) ?: return@resourceEntry null
                    val mime = resource.getAttribute("protocolInfo").split(':').getOrNull(2)
                    if (mime != null && mime != "*" && mime.substringBefore('/') !in MEDIA_TYPES) {
                        return@resourceEntry null
                    }
                    Entry(title, uri = url.toString(), mime = mime)
                }.firstOrNull()
            } else null
        }
        val returned = result["NumberReturned"]?.toIntOrNull()?.coerceIn(0, 24) ?: items.size.coerceAtMost(24)
        val next = offset + returned
        val total = result["TotalMatches"]?.toIntOrNull() ?: next
        return Page(entries, next.takeIf { returned > 0 && next < total && next <= 100_000 })
    }

    private fun describe(location: URI): Server? {
        val doc = SecureSoapXml.parse(fetchXml(location))
        val services = doc.getElementsByTagNameNS("*", "service")
        for (index in 0 until services.length) {
            val children = (services.item(index) as? Element)?.children() ?: continue
            val type = children.firstOrNull { it.localName == "serviceType" }?.textContent?.trim() ?: continue
            if (!type.matches(CONTENT_DIRECTORY)) continue
            val path = children.firstOrNull { it.localName == "controlURL" }?.textContent?.trim() ?: continue
            val control = sameHost(location.resolve(path).toString(), location.host) ?: continue
            val name = doc.getElementsByTagNameNS("*", "friendlyName").item(0)?.textContent?.trim()
                ?.take(100)?.takeIf { it.isNotBlank() } ?: location.host
            return Server(name, control, type)
        }
        return null
    }

    private fun fetchXml(uri: URI, service: String? = null, body: String? = null): String {
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 1_500
            connection.readTimeout = 1_500
            if (service != null && body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("SOAPAction", "\"$service#Browse\"")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val dst = CharArray(SecureSoapXml.MAX_XML_CHARS + 1)
                var offset = 0
                val deadline = System.nanoTime() + 3_000_000_000L
                while (offset < dst.size) {
                    check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
                    val read = reader.read(dst, offset, dst.size - offset)
                    if (read < 0) break
                    offset += read
                }
                require(offset <= SecureSoapXml.MAX_XML_CHARS)
                String(dst, 0, offset)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sameHost(value: String, host: String): URI? {
        if (!app.ember.airreceiver.util.FetchableUrlPolicy.isFetchable(value)) return null
        return URI(value).takeIf { it.host == host }
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val MEDIA_TYPES = setOf("audio", "video", "image")
        private val CONTENT_DIRECTORY = Regex("urn:schemas-upnp-org:service:ContentDirectory:[1-9][0-9]?")
    }
}
