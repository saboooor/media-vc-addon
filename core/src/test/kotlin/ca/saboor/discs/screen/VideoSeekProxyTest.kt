package ca.saboor.discs.screen

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VideoSeekProxyTest {
    @TempDir lateinit var directory: Path

    @Test fun `initial playback supports MP4 with metadata after media data`() {
        assumeTrue(runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false), "FFmpeg is required")
        val movie = directory.resolve("original.mp4").toFile()
        val log = directory.resolve("ffmpeg.log").toFile()
        fun run(args: List<String>, input: java.io.File? = null): Int {
            val builder = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-nostdin") + args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log)
            if (input != null) builder.redirectInput(input)
            val process = builder.start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "FFmpeg timed out")
                return process.exitValue()
            } finally { process.destroyForcibly() }
        }
        // Default MP4 has trailing moov. Exceed the probe buffer to require a
        // backward seek after reading metadata, as original-quality files do.
        assertEquals(0, run(listOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
            "-frames:v", "120", "-c:v", "mpeg4", "-q:v", "2", movie.path)), log.readText())
        val bytes = movie.readBytes()
        assertTrue(bytes.size > 262144)
        assertNotEquals(0, run(listOf("-i", "pipe:0", "-frames:v", "1", "-f", "null", "-"), movie),
            "Fixture must reproduce failure on non-seekable input")

        val ranges = CopyOnWriteArrayList<Int>()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/video") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                ranges.add(start)
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, (bytes.size - start).toLong())
                exchange.responseBody.use { it.write(bytes, start, bytes.size - start) }
            } catch (_: java.io.IOException) {
                // FFmpeg closes range requests when it has enough probe data.
            } finally { exchange.close() }
        }
        source.start()
        try {
            VideoSeekProxy("http://127.0.0.1:${source.address.port}/video", "test-agent").use { proxy ->
                val frame = directory.resolve("frame.rgb").toFile()
                val args = VideoPlayer.proxiedInputOptions() + listOf("-i", proxy.url,
                    "-map", "0:v:0", "-an", "-vf", VideoPlayer.videoFilter(15, 16, 16),
                    "-frames:v", "1", "-threads:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb48le", frame.path)
                assertEquals(0, run(args), log.readText())
                assertEquals(16L * 16 * 6, frame.length())
                assertTrue(frame.readBytes().any { it != 0.toByte() })
                assertTrue(ranges.any { it > 0 }, "Decoder must seek while opening the MP4")
            }
        } finally { source.stop(0) }
    }

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

    @Test fun `extractor media headers reach upstream and credential redirects are refused`() {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var agent: String? = null
        var referer: String? = null
        var cookie: String? = null
        var destinationCalled = false
        source.createContext("/video") { exchange ->
            agent = exchange.requestHeaders.getFirst("User-Agent")
            referer = exchange.requestHeaders.getFirst("Referer")
            cookie = exchange.requestHeaders.getFirst("Cookie")
            exchange.responseHeaders.set("Location", "/destination")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        source.createContext("/destination") { exchange ->
            destinationCalled = true
            exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        source.start()
        try {
            VideoSeekProxy("http://127.0.0.1:${source.address.port}/video", "default-agent",
                sourceHeaders = mapOf("User-Agent" to "extractor-agent", "Referer" to "https://www.youtube.com/", "Cookie" to "private-session")).use { proxy ->
                val request = URI(proxy.url).toURL().openConnection() as HttpURLConnection
                assertEquals(502, request.responseCode)
                assertEquals("extractor-agent", agent)
                assertEquals("https://www.youtube.com/", referer)
                assertEquals("private-session", cookie)
                assertFalse(destinationCalled)
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
