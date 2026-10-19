package su.plo.voice.discs.screen

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A per-playback loopback bridge: Java handles TLS, while FFmpeg retains HTTP range seeking. */
internal class VideoSeekProxy(private val source: String, private val userAgent: String) : AutoCloseable {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "disc-video-seek").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val path = "/${UUID.randomUUID()}"
    val url: String get() = "http://127.0.0.1:${server.address.port}$path"

    init {
        server.executor = executor
        server.createContext(path, ::handle)
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        var connection: HttpURLConnection? = null
        try {
            if (exchange.requestURI.path != path || exchange.requestMethod !in listOf("GET", "HEAD")) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            var uri = URI(source)
            for (redirect in 0..5) {
                val upstream = uri.toURL().openConnection() as HttpURLConnection
                connection = upstream
                connections.add(upstream)
                upstream.instanceFollowRedirects = false
                upstream.connectTimeout = 10_000
                upstream.readTimeout = 20_000
                upstream.requestMethod = exchange.requestMethod
                upstream.setRequestProperty("User-Agent", userAgent)
                exchange.requestHeaders.getFirst("Range")?.let { upstream.setRequestProperty("Range", it) }
                exchange.requestHeaders.getFirst("If-Range")?.let { upstream.setRequestProperty("If-Range", it) }
                val status = upstream.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = upstream.getHeaderField("Location") ?: error("Missing redirect target")
                    uri = uri.resolve(location)
                    require(uri.scheme in listOf("http", "https"))
                    connections.remove(upstream)
                    upstream.disconnect()
                    continue
                }
                for (header in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length")) {
                    upstream.getHeaderField(header)?.let { exchange.responseHeaders.set(header, it) }
                }
                if (exchange.requestMethod == "HEAD" || status == 204 || status == 304) {
                    exchange.sendResponseHeaders(status, -1)
                } else {
                    val length = upstream.contentLengthLong
                    exchange.sendResponseHeaders(status, if (length > 0) length else 0)
                    val input = if (status >= 400) upstream.errorStream else upstream.inputStream
                    input?.use { it.copyTo(exchange.responseBody) }
                }
                return
            }
            exchange.sendResponseHeaders(502, -1)
        } catch (_: Exception) {
            // FFmpeg closes outstanding range requests when it seeks again or playback stops.
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            connection?.let { connections.remove(it); it.disconnect() }
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        connections.forEach { it.disconnect() }
        connections.clear()
        executor.shutdownNow()
    }
}
