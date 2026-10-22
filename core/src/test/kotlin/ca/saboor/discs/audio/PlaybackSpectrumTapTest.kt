package ca.saboor.discs.audio

import ca.saboor.discs.visualizer.SpectrumAnalyzer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats

class PlaybackSpectrumTapTest {
    @Test fun `prepared audio does not move visualizer until frames are transmitted`() {
        val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val analyzer = SpectrumAnalyzer()
        val tone = ByteBuffer.allocate(format.totalSampleCount() * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(format.chunkSampleCount) { sample ->
            val value = (sin(2 * PI * 1000 * sample / format.sampleRate) * 16000).toInt().toShort()
            repeat(format.channelCount) { tone.putShort(value) }
        }
        val frame = tone.array()
        val original = frame.copyOf()
        PlaybackSpectrumTap(format).use { tap ->
            // Decoded frames can wait in LavaPlayer's buffer without publishing future energy.
            assertTrue(analyzer.levels.all { it == 0f })
            repeat(3) { tap.accept(frame, analyzer) }
            assertTrue(analyzer.levels.max() > 0f)
            assertTrue(analyzer.levels.indices.maxBy { analyzer.levels[it] } in 7..9)
            assertArrayEquals(original, frame, "Analysis must not change transmitted audio")
            repeat(3) { tap.accept(ByteArray(frame.size), analyzer) }
            assertTrue(analyzer.levels.all { it == 0f })
        }
    }
}
