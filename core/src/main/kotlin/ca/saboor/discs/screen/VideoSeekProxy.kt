package ca.saboor.discs.screen

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A per-playback loopback bridge: Java handles TLS, while FFmpeg retains HTTP range seeking. */
internal class VideoSeekProxy(private val source: String, private val userAgent: String,
    private val authorization: String? = null,
    private val sourceHeaders: Map<String, String> = emptyMap()
) : AutoCloseable {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "disc-video-seek").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val path = "/${UUID.randomUUID()}"
    private val fetchPath = "/fetch-${UUID.randomUUID()}"
    val url: String get() = "http://127.0.0.1:${server.address.port}$path"

    init {
        server.executor = executor
        server.createContext(path, ::handle)
        server.createContext(fetchPath, ::handleFetch)
        server.start()
    }

    private fun handleFetch(exchange: HttpExchange) {
        val rawQuery = exchange.requestURI.rawQuery.orEmpty()
        val encoded = rawQuery.split('&').firstOrNull { it.startsWith("u=") }?.substringAfter('=')
        val target = encoded?.let { URLDecoder.decode(it, Charsets.UTF_8) }
        val targetUri = target?.let { runCatching { URI(it) }.getOrNull() }
        if (targetUri == null || targetUri.scheme !in listOf("http", "https")) {
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
            return
        }
        proxyUri(exchange, targetUri)
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestURI.path != path || exchange.requestMethod !in listOf("GET", "HEAD")) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        proxyUri(exchange, URI(source))
    }

    private fun proxyUri(exchange: HttpExchange, initialUri: URI) {
        var connection: HttpURLConnection? = null
        try {
            var uri = initialUri
            for (redirect in 0..5) {
                val upstream = uri.toURL().openConnection() as HttpURLConnection
                connection = upstream
                connections.add(upstream)
                upstream.instanceFollowRedirects = false
                upstream.connectTimeout = 10_000
                upstream.readTimeout = if (authorization != null) 60_000 else 20_000
                upstream.requestMethod = exchange.requestMethod
                upstream.setRequestProperty("User-Agent", userAgent)
                sourceHeaders.filterKeys { it.lowercase() !in setOf("host", "connection", "content-length", "range", "if-range") }
                    .forEach { (name, value) -> upstream.setRequestProperty(name, value) }
                authorization?.let { upstream.setRequestProperty("Authorization", it) }
                exchange.requestHeaders.getFirst("Range")?.let { upstream.setRequestProperty("Range", it) }
                exchange.requestHeaders.getFirst("If-Range")?.let { upstream.setRequestProperty("If-Range", it) }
                val status = upstream.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    // Authenticated Jellyfin streams must never forward credentials to redirects.
                    if (authorization != null || sourceHeaders.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) }) {
                        logger.warn("Video upstream returned redirect $status for ${VideoPlayer.describeSource(source)}; use the final media server URL (credentials were not forwarded)")
                        exchange.sendResponseHeaders(502, -1)
                        return
                    }
                    val location = upstream.getHeaderField("Location") ?: error("Missing redirect target")
                    uri = uri.resolve(location)
                    require(uri.scheme in listOf("http", "https"))
                    connections.remove(upstream)
                    upstream.disconnect()
                    continue
                }
                if (status >= 400) logger.warn("Video upstream returned HTTP $status for ${VideoPlayer.describeSource(source)}")
                for (header in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length")) {
                    upstream.getHeaderField(header)?.let { exchange.responseHeaders.set(header, it) }
                }
                if (exchange.requestMethod == "HEAD" || status == 204 || status == 304) {
                    exchange.sendResponseHeaders(status, -1)
                } else {
                    val contentType = upstream.contentType.orEmpty()
                    val isPlaylist = status in 200..299 && (
                        contentType.contains("mpegurl", ignoreCase = true) ||
                            uri.path?.endsWith(".m3u8", ignoreCase = true) == true
                        )
                    if (isPlaylist) {
                        val playlistText = upstream.inputStream.use { it.readAllBytes() }.toString(Charsets.UTF_8)
                        val rewritten = rewritePlaylist(playlistText, uri).toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.remove("Content-Length")
                        exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                        exchange.sendResponseHeaders(status, rewritten.size.toLong())
                        exchange.responseBody.use { it.write(rewritten) }
                    } else {
                        val length = upstream.contentLengthLong
                        exchange.sendResponseHeaders(status, if (length > 0) length else 0)
                        val input = if (status >= 400) upstream.errorStream else upstream.inputStream
                        input?.use { it.copyTo(exchange.responseBody, 64 * 1024) }
                    }
                }
                return
            }
            exchange.sendResponseHeaders(502, -1)
        } catch (e: Exception) {
            if (e is java.net.SocketTimeoutException) {
                logger.warn("Video upstream timed out for ${VideoPlayer.describeSource(source)}; FFmpeg may reconnect")
            } else if (e is java.net.ConnectException || e is javax.net.ssl.SSLException) {
                logger.warn("Video upstream connection failed for ${VideoPlayer.describeSource(source)} (${e.javaClass.simpleName})")
            }
            // FFmpeg closes outstanding range requests when it seeks again or playback stops.
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            connection?.let { connections.remove(it); it.disconnect() }
            exchange.close()
        }
    }

    private fun resolvePlaylistUri(base: URI, raw: String): String {
        val resolved = base.resolve(raw)
        if (!resolved.host.equals(base.host, ignoreCase = true)) return resolved.toString()
        // Reverse-proxied media servers (e.g. Jellyfin) often embed their internal backend port
        // or scheme in playlist segment URLs; keep the public entry scheme and authority instead.
        return buildString {
            append(base.scheme).append("://").append(base.rawAuthority)
            resolved.rawPath?.let { append(it) }
            resolved.rawQuery?.let { append('?').append(it) }
            resolved.rawFragment?.let { append('#').append(it) }
        }
    }

    private fun rewritePlaylist(playlist: String, base: URI): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        val proxyBase = "http://127.0.0.1:${server.address.port}$fetchPath?u="
        return playlist.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> uriAttrRegex.replace(line) { match ->
                    val resolved = resolvePlaylistUri(base, match.groupValues[1])
                    """URI="$proxyBase${URLEncoder.encode(resolved, Charsets.UTF_8)}""""
                }
                else -> "$proxyBase${URLEncoder.encode(resolvePlaylistUri(base, trimmed), Charsets.UTF_8)}"
            }
        }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("VideoSeekProxy")
    }

    override fun close() {
        server.stop(0)
        connections.forEach { it.disconnect() }
        connections.clear()
        executor.shutdownNow()
    }
}
