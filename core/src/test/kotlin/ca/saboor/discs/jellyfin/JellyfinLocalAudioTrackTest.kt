package ca.saboor.discs.jellyfin

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioReference
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack

class JellyfinLocalAudioTrackTest {
    @TempDir lateinit var folder: Path

    @Test fun `original AC3 audio decodes locally with authentication seeking and clean stop`() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor() == 0 }.getOrDefault(false))
        val file = folder.resolve("original.mkv").toFile()
        val generate = ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
            "sine=frequency=440:duration=8", "-c:a", "ac3", file.path)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertTrue(generate.waitFor(15, TimeUnit.SECONDS))
        assertEquals(0, generate.exitValue())
        val bytes = file.readBytes()
        val delayNextRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newCachedThreadPool()
        server.executor = workers
        server.createContext("/Videos/") { exchange ->
            try {
                if (delayNextRead.getAndSet(false)) Thread.sleep(11_000)
                requests.add(exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization"))
                val range = exchange.requestHeaders.getFirst("Range")
                val offset = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                exchange.responseHeaders.set("Content-Type", "video/x-matroska")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                if (offset >= bytes.size) exchange.sendResponseHeaders(416, -1)
                else {
                    if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (bytes.size - offset).toLong())
                    exchange.responseBody.write(bytes, offset, bytes.size - offset)
                }
            } catch (_: java.io.IOException) { /* Decoder may close a probe or seek request early. */ }
            finally { exchange.close() }
        }
        server.start()
        val manager = DefaultAudioPlayerManager()
        manager.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val library = JellyfinLibrary(folder.resolve("jellyfin.json"))
        val connection = library.connect(UUID.randomUUID(), JellyfinClient.Session(
            "http://127.0.0.1:${server.address.port}/", "0123456789abcdef0123456789abcdef", "secret-token", "device"))
        val reference = library.grant(connection, JellyfinClient.Media(
            "abcdef0123456789abcdef0123456789", "Movie", "", "", 8_000, "Movie"))
        library.setTranscoding(connection.owner, false)
        val source = JellyfinAudioSourceManager(library)
        val player = manager.createPlayer()
        try {
            val track = source.loadItem(manager, AudioReference(reference, null)) as AudioTrack
            assertTrue(track is JellyfinLocalAudioTrack)
            assertTrue(track.makeClone() is JellyfinLocalAudioTrack)
            assertEquals(reference, track.identifier)
            assertEquals(8_000L, track.duration)
            val startupStalls = java.util.concurrent.atomic.AtomicInteger()
            player.addListener(object : su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter() {
                override fun onTrackStuck(player: su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer,
                    stalled: AudioTrack, thresholdMs: Long) {
                    if (stalled is JellyfinLocalAudioTrack && stalled.awaitingFirstFrame) startupStalls.incrementAndGet()
                    else fail<Unit>("Audio stalled after startup")
                }
            })
            track.position = 1_000
            delayNextRead.set(true)
            player.playTrack(track)
            fun awaitFrameAt(position: Long): ByteArray {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (System.nanoTime() < deadline) {
                    val frame = player.provide()
                    if (frame != null && frame.timecode >= position) return frame.data
                    Thread.sleep(5)
                }
                fail<Unit>("No decoded audio at ${position}ms")
                return byteArrayOf()
            }
            assertTrue(awaitFrameAt(1_000).any { it != 0.toByte() })
            assertTrue(startupStalls.get() > 0, "Startup must survive LavaPlayer's ten-second stuck event")
            assertFalse((track as JellyfinLocalAudioTrack).awaitingFirstFrame)
            track.position = 4_000
            assertTrue(awaitFrameAt(4_000).any { it != 0.toByte() })
            player.stopTrack()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (Thread.getAllStackTraces().keys.any { it.name == "disc-jellyfin-audio" && it.isAlive } && System.nanoTime() < deadline)
                Thread.sleep(10)
            assertFalse(Thread.getAllStackTraces().keys.any { it.name == "disc-jellyfin-audio" && it.isAlive })
            assertTrue(requests.all { it.first.endsWith("/stream?static=true") })
            assertTrue(requests.all { it.second?.contains("Token=\"secret-token\"") == true })
        } finally {
            player.destroy(); source.shutdown(); manager.shutdown(); server.stop(0); workers.shutdownNow()
        }
    }
}
