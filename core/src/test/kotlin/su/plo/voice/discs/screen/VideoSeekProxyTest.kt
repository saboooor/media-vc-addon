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
        source.createContext("/video") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            agent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.responseHeaders.set("Content-Range", "bytes 4-7/10")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write("4567".toByteArray()) }
            exchange.close()
        }
        source.start()
        try {
            VideoSeekProxy("http://127.0.0.1:${source.address.port}/video", "test-agent").use { proxy ->
                val request = URI(proxy.url).toURL().openConnection() as HttpURLConnection
                request.setRequestProperty("Range", "bytes=4-7")
                assertEquals(206, request.responseCode)
                assertEquals("bytes 4-7/10", request.getHeaderField("Content-Range"))
                assertEquals("4567", request.inputStream.use { it.readBytes().decodeToString() })
                assertEquals("bytes=4-7", range)
                assertEquals("test-agent", agent)
                request.disconnect()
            }
        } finally { source.stop(0) }
    }

    @Test fun `proxy uses loopback and rejects unrelated paths`() {
        VideoSeekProxy("https://example.com/video", "test-agent").use { proxy ->
            val url = URI(proxy.url)
            assertEquals("127.0.0.1", url.host)
            val request = URI("${proxy.url}/unrelated").toURL().openConnection() as HttpURLConnection
            assertEquals(404, request.responseCode)
            request.disconnect()
        }
    }
}
