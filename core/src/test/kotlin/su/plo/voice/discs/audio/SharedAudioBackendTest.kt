package su.plo.voice.discs.audio

import de.maxhenkel.voicechat.api.audiochannel.AudioChannel
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import su.plo.voice.api.server.audio.source.ServerProximitySource
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.PlasmoAudioPlayerManager
import su.plo.voice.discs.utils.KOIN_INSTANCE
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SharedAudioBackendTest {
    @TempDir lateinit var directory: Path

    private fun load(loader: DefaultAudioPlayerManager): AudioTrack {
        val pcm = ByteBuffer.allocate(9600 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(9600) { val value = (kotlin.math.sin(it * 0.1) * 12000).toInt().toShort(); pcm.putShort(value).putShort(value) }
        val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
        wav.put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
        val path = directory.resolve("sound.wav")
        Files.write(path, wav.array())
        loader.registerSourceManager(LocalAudioSourceManager())
        val loaded = CompletableFuture<AudioTrack>()
        loader.loadItem(path.toString(), object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { loaded.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { loaded.completeExceptionally(AssertionError("Unexpected playlist")) }
            override fun noMatches() { loaded.completeExceptionally(AssertionError("Missing WAV")) }
            override fun loadFailed(exception: FriendlyException) { loaded.completeExceptionally(exception) }
        })
        return loaded.get(5, TimeUnit.SECONDS)
    }

    @Test fun `voice backends share one player and identical encoded frames and clean up on cancellation`() = runBlocking {
        for (cancel in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val previous = runCatching { KOIN_INSTANCE }.getOrNull()
            val koin = koinApplication { modules(module { single<CoroutineScope> { scope } }) }
            KOIN_INSTANCE = koin.koin
            val manager = PlasmoAudioPlayerManager()
            val loader = DefaultAudioPlayerManager()
            val pvFrames = mutableListOf<ByteArray>()
            val svcFrames = mutableListOf<ByteArray>()
            val removed = AtomicInteger()
            val flushed = AtomicInteger()
            val ended = AtomicInteger()
            val pv = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ServerProximitySource::class.java)) { _, method, args ->
                when (method.name) {
                    "sendAudioFrame" -> { pvFrames += args[0] as ByteArray; true }
                    "sendAudioEnd" -> { ended.incrementAndGet(); true }
                    "remove" -> { removed.incrementAndGet(); null }
                    else -> null
                }
            } as ServerProximitySource<*>
            val svc = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioChannel::class.java)) { _, method, args ->
                when (method.name) {
                    "send" -> { svcFrames += args[0] as ByteArray; null }
                    "flush" -> { flushed.incrementAndGet(); null }
                    else -> null
                }
            } as AudioChannel
            val controls = PlaybackControls().apply { togglePause() }
            try {
                val job = manager.startTrackJob(load(loader), pv, 32, svc, controls)
                withTimeout(5000) { while (controls.duration == 0L) delay(1) }
                val players = PlaybackControls::class.java.getDeclaredField("players").apply { isAccessible = true }
                assertEquals(1, (players.get(controls) as Collection<*>).size)
                assertTrue(pvFrames.isEmpty())
                assertTrue(svcFrames.isEmpty())
                if (cancel) job.cancelAndJoin() else {
                    controls.togglePause()
                    withTimeout(5000) { job.join() }
                    assertTrue(pvFrames.isNotEmpty())
                    assertEquals(pvFrames.size, svcFrames.size)
                    pvFrames.indices.forEach { assertSame(pvFrames[it], svcFrames[it]) }
                }
                assertNull(controls.failure)
                assertEquals(0, (players.get(controls) as Collection<*>).size)
                assertEquals(1, removed.get())
                assertTrue(ended.get() >= 1)
                assertTrue(flushed.get() >= 1)
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                manager.shutdown()
                loader.shutdown()
                koin.close()
                if (previous != null) KOIN_INSTANCE = previous
            }
        }
    }
}
