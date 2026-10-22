package ca.saboor.discs.audio

import ca.saboor.discs.PlaybackControls
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack

class SharedStereoPlayerTest {
    @TempDir lateinit var directory: Path

    @Test fun `loaded tracks execute as shared PCM and pause and seek affect both channels together`() {
        val loader = DefaultAudioPlayerManager()
        val playback = DefaultAudioPlayerManager().apply {
            configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
            configuration.isFilterHotSwapEnabled = true
            setUseSeekGhosting(false)
            frameBufferDuration = 200
        }
        val player = playback.createPlayer()
        val controls = PlaybackControls()
        try {
            // Ten seconds of lossless stereo: right is always the inverse of left.
            val pcm = ByteBuffer.allocate(480_000 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(480_000) { sample ->
                val value = ((sample % 1000) * 20).toShort()
                pcm.putShort(value).putShort((-value).toShort())
            }
            val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(2).putInt(48_000).putInt(192_000).putShort(4).putShort(16)
            wav.put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
            val path = directory.resolve("stereo.wav")
            Files.write(path, wav.array())
            loader.registerSourceManager(LocalAudioSourceManager())
            val loaded = CompletableFuture<AudioTrack>()
            loader.loadItem(path.toString(), object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) { loaded.complete(track) }
                override fun playlistLoaded(playlist: AudioPlaylist) { loaded.completeExceptionally(AssertionError("Unexpected playlist")) }
                override fun noMatches() { loaded.completeExceptionally(AssertionError("WAV was not loaded")) }
                override fun loadFailed(exception: FriendlyException) { loaded.completeExceptionally(exception) }
            })
            player.playTrack(loaded.get(5, TimeUnit.SECONDS))
            controls.attach(player)
            val first = player.provide(5, TimeUnit.SECONDS)
            assertNotNull(first)
            assertEquals(StandardAudioDataFormats.DISCORD_PCM_S16_LE, first.format)

            controls.togglePause()
            assertNull(player.provide(), "Pausing the single player must stop both channels")
            controls.seekTo(400)
            controls.togglePause()
            // LavaPlayer applies seeks asynchronously on its decoder thread. Both
            // channels must arrive at the new position together once it takes effect.
            val sought = generateSequence { player.provide(5, TimeUnit.SECONDS) }
                .take(50).firstOrNull { it.timecode >= 400 }
            assertNotNull(sought)
            assertTrue(sought!!.timecode in 400..420, "Unexpected seek position: ${sought.timecode} ms")
            val samples = ByteBuffer.wrap(sought.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(960 * 2, samples.remaining())
            repeat(960) {
                assertEquals(-samples.get().toInt(), samples.get().toInt(), "Channel samples must share the same position after seeking")
            }
        } finally {
            controls.detach(player)
            player.destroy()
            playback.shutdown()
            loader.shutdown()
        }
    }
}
