package su.plo.voice.discs.screen

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class JellyfinVideoPlaybackTest {
    @TempDir lateinit var directory: Path

    @Test fun `authenticated MKV proxy preserves moving frames at matching and capped FPS`() {
        assumeTrue(runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false), "FFmpeg is required for the Jellyfin integration test")

        val video = directory.resolve("movie.mkv")
        val generate = ProcessBuilder("ffmpeg", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=size=64x64:rate=24:duration=3",
            "-f", "lavfi", "-i", "sine=duration=3", "-c:v", "mpeg4", "-bf", "2",
            "-c:a", "aac", video.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertEquals(0, generate.waitFor())
        val media = video.toFile().readBytes()
        val credentials = "MediaBrowser Token=\"test-token\""
        val receivedAuth = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/Videos/movie/stream") { exchange ->
            receivedAuth.add(exchange.requestHeaders.getFirst("Authorization") ?: "missing")
            val range = exchange.requestHeaders.getFirst("Range")
            val offset = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            try {
                if (offset >= media.size) {
                    exchange.responseHeaders.set("Content-Range", "bytes */${media.size}")
                    exchange.sendResponseHeaders(416, -1)
                } else {
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    exchange.responseHeaders.set("Content-Type", "video/x-matroska")
                    if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $offset-${media.lastIndex}/${media.size}")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (media.size - offset).toLong())
                    exchange.responseBody.write(media, offset, media.size - offset)
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            for (cap in listOf(20, 24)) {
                VideoSeekProxy("http://127.0.0.1:${server.address.port}/Videos/movie/stream?static=true",
                    "test-agent", credentials).use { proxy ->
                    val filter = VideoPlayer.videoFilter(cap, 64, 64)
                    val expected = decode(video.toString(), emptyList(), filter.removeSuffix(",realtime"))
                    val actual = decode(proxy.url, VideoPlayer.proxiedInputOptions(), filter)
                    assertArrayEquals(expected, actual, "HTTP playback must match local MKV decoding at $cap FPS")
                    val frames = actual.asList().chunked(64 * 64 * 3)
                    assertEquals(3 * cap, frames.size)
                    assertEquals(frames.size, frames.toSet().size, "Playback must not repeat frames")
                }
            }
            assertTrue(receivedAuth.isNotEmpty())
            assertTrue(receivedAuth.all { it == credentials }, "Every upstream request must remain authenticated")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun decode(source: String, inputOptions: List<String>, filter: String): ByteArray {
        val command = listOf("ffmpeg", "-v", "error") + inputOptions + listOf(
            "-i", source, "-map", "0:v:0", "-an", "-sn", "-dn", "-vf", filter,
            "-threads:v", "1", "-flush_packets", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        return try {
            val bytes = process.inputStream.use { it.readBytes() }
            assertEquals(0, process.waitFor())
            bytes
        } finally { process.destroyForcibly() }
    }
}
