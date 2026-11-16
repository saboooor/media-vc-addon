package ca.saboor.discs.audio

import com.sun.net.httpserver.HttpServer
import java.io.DataInput
import java.io.DataOutput
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.*

class SoundCloudFallbackAudioSourceManagerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `accepts soundcloud urls and search queries only`() {
        assertTrue(SoundCloudFallbackAudioSourceManager.accepts("https://soundcloud.com/artist/track"))
        assertTrue(SoundCloudFallbackAudioSourceManager.accepts("https://on.soundcloud.com/abc123"))
        assertTrue(SoundCloudFallbackAudioSourceManager.accepts("https://snd.sc/xyz"))
        assertTrue(SoundCloudFallbackAudioSourceManager.accepts("scsearch:lofi beats"))
        assertFalse(SoundCloudFallbackAudioSourceManager.accepts("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(SoundCloudFallbackAudioSourceManager.accepts("https://soundcloud.com.evil.example/track"))
    }

    @Test
    fun `falls back to yt-dlp when native SoundCloud fails and plays audio`() {
        val pcmBytes = 38_400 // 200ms at 48kHz stereo 16-bit
        val wav = ByteArray(44 + pcmBytes)
        "RIFF".toByteArray().copyInto(wav, 0)
        wav[4] = ((wav.size - 8) and 255).toByte(); wav[5] = (((wav.size - 8) shr 8) and 255).toByte()
        "WAVEfmt ".toByteArray().copyInto(wav, 8)
        wav[16] = 16; wav[20] = 1; wav[22] = 2
        wav[24] = 0x80.toByte(); wav[25] = 0xBB.toByte() // 48000 Hz
        wav[28] = 0x00; wav[29] = 0xEE.toByte(); wav[30] = 0x02 // byte rate 192000
        wav[32] = 4; wav[34] = 16
        "data".toByteArray().copyInto(wav, 36)
        wav[40] = (pcmBytes and 255).toByte(); wav[41] = ((pcmBytes shr 8) and 255).toByte()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio.wav") { exchange ->
            exchange.responseHeaders.set("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            val streamUrl = "http://127.0.0.1:${server.address.port}/audio.wav"
            val script = directory.resolve("fake-ytdlp.sh").toFile()
            script.writeText(
                """
                #!/usr/bin/env bash
                cat <<'JSON'
                {"webpage_url":"https://soundcloud.com/artist/track","title":"SC Test","uploader":"SC Artist","duration":12.5,"thumbnail":"https://i1.sndcdn.com/art.jpg","url":"$streamUrl","format":"http_mp3_128"}
                JSON
                """.trimIndent()
            )
            Files.setPosixFilePermissions(
                script.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            )

            val failingNative = object : AudioSourceManager {
                override fun getSourceName() = "soundcloud"
                override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
                    throw FriendlyException("Client ID expired", FriendlyException.Severity.COMMON, null)
                }
                override fun isTrackEncodable(track: AudioTrack) = false
                override fun encodeTrack(track: AudioTrack, output: DataOutput) {}
                override fun decodeTrack(info: AudioTrackInfo, input: DataInput): AudioTrack? = null
                override fun shutdown() {}
            }

            val manager = DefaultAudioPlayerManager()
            val sc = SoundCloudFallbackAudioSourceManager(failingNative, ytdlpExecutable = { script.absolutePath })
            manager.registerSourceManager(sc)
            try {
                val track = sc.loadItem(manager, AudioReference("https://soundcloud.com/artist/track", null)) as AudioTrack
                assertEquals("SC Test", track.info.title)
                assertEquals("SC Artist", track.info.author)
                assertEquals(12_500L, track.info.length)
                assertEquals("https://i1.sndcdn.com/art.jpg", track.info.artworkUrl)

                val player = manager.createPlayer()
                try {
                    player.playTrack(track)
                    val deadline = System.currentTimeMillis() + 5_000L
                    var frame = player.provide()
                    while (frame == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20)
                        frame = player.provide()
                    }
                    assertNotNull(frame, "SoundCloud fallback track should decode PCM/Opus frames")
                } finally {
                    player.destroy()
                }
            } finally {
                manager.shutdown()
            }
        } finally {
            server.stop(0)
        }
    }
}
