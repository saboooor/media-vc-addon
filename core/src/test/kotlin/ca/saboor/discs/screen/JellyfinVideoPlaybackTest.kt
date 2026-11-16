package ca.saboor.discs.screen

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
            for ((cap, startMs) in listOf(20 to 0, 24 to 0, 24 to 1000, 24 to 0)) {
                VideoSeekProxy("http://127.0.0.1:${server.address.port}/Videos/movie/stream?static=true",
                    "test-agent", credentials).use { proxy ->
                    val filter = VideoPlayer.videoFilter(cap, 64, 64)
                    val expected = decode(video.toString(), emptyList(), filter.removeSuffix(",realtime"), startMs)
                    val actual = decode(proxy.url, VideoPlayer.proxiedInputOptions(), filter.removeSuffix(",realtime"), startMs, buffered = true)
                    assertArrayEquals(expected, actual, "HTTP playback must match local MKV decoding at $cap FPS")
                    val frames = actual.asList().chunked(64 * 64 * 6)
                    assertEquals((3 - startMs / 1000) * cap, frames.size)
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

    private fun decode(source: String, inputOptions: List<String>, filter: String, startMs: Int, buffered: Boolean = false): ByteArray {
        val seek = if (startMs > 0) listOf("-ss", (startMs / 1000.0).toString()) else emptyList()
        val command = listOf("ffmpeg", "-v", "error") + inputOptions + VideoPlayer.decoderInputOptions() + seek + listOf(
            "-i", source, "-map", "0:v:0", "-an", "-sn", "-dn", "-vf", filter,
            "-threads:v", "1", "-flush_packets", "1", "-f", "rawvideo", "-pix_fmt", "rgb48le", "pipe:1")
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        return try {
            val bytes = process.inputStream.use { input ->
                if (!buffered) input.readBytes() else runBlocking {
                    // Exercise backpressure and pooled RGB48 frames with a slow/paused viewer.
                    val buffer = VideoDecodeBuffer(64 * 64 * 6, 64 * 64 * 6 * 3)
                    val producer = launch(Dispatchers.IO) {
                        buffer.decode { pixels ->
                            var offset = 0
                            while (offset < pixels.size) {
                                val count = input.read(pixels, offset, pixels.size - offset)
                                if (count < 0) {
                                    check(offset == 0) { "Truncated frame" }
                                    return@decode null
                                }
                                offset += count
                            }
                            0L
                        }
                    }
                    val output = ByteArrayOutputStream()
                    try {
                        delay(100) // Fill the pool before presentation resumes.
                        for (frame in buffer.frames) {
                            try {
                                output.write(frame.pixels)
                                delay(2)
                            } finally { buffer.release(frame) }
                        }
                        producer.join()
                        output.toByteArray()
                    } finally { producer.cancelAndJoin() }
                }
            }
            assertEquals(0, process.waitFor())
            bytes
        } finally { process.destroyForcibly() }
    }
}
