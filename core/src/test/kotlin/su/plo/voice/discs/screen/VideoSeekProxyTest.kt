package su.plo.voice.discs.screen

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoSeekProxyTest {
    @Test fun `seeks forward byte ranges and preserve partial responses`() {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var range: String? = null
        var agent: String? = null
        var authorization: String? = null
        source.createContext("/video") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            agent = exchange.requestHeaders.getFirst("User-Agent")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.set("Content-Range", "bytes 4-7/10")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write("4567".toByteArray()) }
            exchange.close()
        }
        source.start()
        try {
            VideoSeekProxy("http://127.0.0.1:${source.address.port}/video", "test-agent", "MediaBrowser Token=\"secret\"").use { proxy ->
                val request = URI(proxy.url).toURL().openConnection() as HttpURLConnection
                request.setRequestProperty("Range", "bytes=4-7")
                assertEquals(206, request.responseCode)
                assertEquals("bytes 4-7/10", request.getHeaderField("Content-Range"))
                assertEquals("4567", request.inputStream.use { it.readBytes().decodeToString() })
                assertEquals("bytes=4-7", range)
                assertEquals("test-agent", agent)
                assertEquals("MediaBrowser Token=\"secret\"", authorization)
                request.disconnect()
            }
        } finally { source.stop(0) }
    }

    @Test fun `authenticated streams use headers and refuse redirects`() {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        var destinationCalled = false
        source.createContext("/video") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.set("Location", "/destination")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        source.createContext("/destination") { exchange ->
            destinationCalled = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        source.start()
        try {
            VideoSeekProxy("http://127.0.0.1:${source.address.port}/video", "test-agent", "MediaBrowser Token=\"secret\"").use { proxy ->
                assertFalse(proxy.url.contains("secret"))
                val request = URI(proxy.url).toURL().openConnection() as HttpURLConnection
                assertEquals(502, request.responseCode)
                assertEquals("MediaBrowser Token=\"secret\"", authorization)
                assertFalse(destinationCalled)
                request.disconnect()
            }
        } finally { source.stop(0) }
    }

    @Test fun `proxy uses loopback and rejects unrelated paths`() {
        VideoSeekProxy("https://example.com/video", "test-agent", "MediaBrowser Token=\"secret\"").use { proxy ->
            val url = URI(proxy.url)
            assertEquals("127.0.0.1", url.host)
            val request = URI("${proxy.url}/unrelated").toURL().openConnection() as HttpURLConnection
            assertEquals(404, request.responseCode)
            request.disconnect()
        }
    }
}
