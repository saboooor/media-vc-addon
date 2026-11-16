package ca.saboor.discs.youtube

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioReference
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack

class YtDlpAudioPlaybackTest {
    @TempDir lateinit var directory: Path

    @Test fun `extracted tracks and clones decode audio with required headers and YouTube identity`() {
        assumeTrue(java.io.File("/bin/sh").canExecute())
        val pcmSize = 48000 * 2 * 2 * 2
        val wave = ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(48000); putInt(192000)
            putShort(4); putShort(16); put("data".toByteArray()); putInt(pcmSize)
            repeat(96000) { sample ->
                val value = (sin(sample * 2 * Math.PI * 440 / 48000) * 16000).toInt().toShort()
                putShort(value); putShort(value)
            }
        }.array()
        val agents = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio.wav") { exchange ->
            try {
                agents += exchange.requestHeaders.getFirst("User-Agent")
                val range = exchange.requestHeaders.getFirst("Range")
                val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                exchange.responseHeaders.set("Content-Type", "audio/wav")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-${wave.lastIndex}/${wave.size}")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, (wave.size - start).toLong())
                exchange.responseBody.use { it.write(wave, start, wave.size - start) }
            } catch (_: java.io.IOException) {
            } finally { exchange.close() }
        }
        server.start()
        val script = directory.resolve("fake-ytdlp").toFile().apply {
            writeText("""#!/bin/sh
cat <<'JSON'
{"id":"abcdefghijk","title":"Song","uploader":"Artist","duration":2,"url":"http://127.0.0.1:${server.address.port}/audio.wav","http_headers":{"User-Agent":"required-test-agent"}}
JSON
""")
            assertTrue(setExecutable(true))
        }
        val manager = DefaultAudioPlayerManager().apply {
            configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
            setUseSeekGhosting(false)
        }
        val source = YtDlpAudioSourceManager(YtDlpClient(script.path))
        manager.registerSourceManager(source)
        try {
            val track = source.loadItem(manager, AudioReference("https://youtu.be/abcdefghijk", null)) as AudioTrack
            assertEquals("abcdefghijk", track.identifier)
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk", track.info.uri)
            assertEquals(2000, track.duration)
            assertTrue(track.isSeekable)
            for (playable in listOf(track, track.makeClone())) {
                val player = manager.createPlayer()
                try {
                    player.playTrack(playable)
                    assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                        while (true) {
                            val frame = player.provide()
                            if (frame != null && frame.data.any { it != 0.toByte() }) break
                            Thread.sleep(5)
                        }
                    }
                } finally { player.destroy() }
            }
            assertTrue(agents.isNotEmpty())
            assertTrue(agents.all { it == "required-test-agent" })
        } finally { manager.shutdown(); server.stop(0) }
    }
}
