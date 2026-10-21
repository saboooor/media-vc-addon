package su.plo.voice.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import java.nio.ShortBuffer

class AudioEffectsTest {

    private class RecordingFilter : UniversalPcmAudioFilter {
        var processedFrames = 0
        var totalLength = 0
        var lastInput: Array<FloatArray>? = null

        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            processedFrames++
            totalLength += length
            lastInput = input.map { it.copyOfRange(offset, offset + length) }.toTypedArray()
        }

        override fun process(input: ShortArray, offset: Int, length: Int) {}
        override fun process(buffer: ShortBuffer) {}
        override fun process(input: Array<ShortArray>, offset: Int, length: Int) {}

        override fun seekPerformed(requested: Long, provided: Long) {}
        override fun flush() {}
        override fun close() {}
    }

    @Test
    fun `empty filter config returns null factory when no speaker channel is set`() {
        val empty = AudioFilterConfig()
        assertNull(AudioFilterChain.buildFactory(empty, null))
    }

    @Test
    fun `eq preset creates filter factory and processes audio`() {
        val config = AudioFilterConfig(eqPreset = "bass")
        val factory = AudioFilterChain.buildFactory(config, null)
        assertNotNull(factory)

        val downstream = RecordingFilter()
        val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val chain = factory!!.buildChain(null, format, downstream)
        assertEquals(1, chain.size)

        val input = arrayOf(
            FloatArray(960) { 0.5f },
            FloatArray(960) { -0.5f }
        )
        (chain[0] as FloatPcmAudioFilter).process(input, 0, 960)
        assertEquals(1, downstream.processedFrames)
        assertEquals(960, downstream.totalLength)
    }

    @Test
    fun `echo filter creates decay over multiple buffers`() {
        val downstream = RecordingFilter()
        val echo = EchoFilter(downstream, sampleRate = 1000, delaySeconds = 0.01, decay = 0.5f) // 10 samples delay
        val input = arrayOf(FloatArray(20) { if (it == 0) 1.0f else 0.0f })
        echo.process(input, 0, 20)

        assertEquals(1, downstream.processedFrames)
        val out = downstream.lastInput!![0]
        assertEquals(1.0f, out[0], 0.001f)
        assertEquals(0.5f, out[10], 0.001f) // Echo after 10 samples
    }

    @Test
    fun `rotation filter pans stereo audio across channels`() {
        val downstream = RecordingFilter()
        val rotation = RotationFilter(downstream, sampleRate = 1000, rotationHz = 25.0)
        val left = FloatArray(100) { 1.0f }
        val right = FloatArray(100) { 1.0f }
        val input = arrayOf(left, right)
        rotation.process(input, 0, 100)

        assertEquals(1, downstream.processedFrames)
        // Check that left and right channels are panned and differ from pure 1.0f
        assertNotEquals(downstream.lastInput!![0][10], downstream.lastInput!![1][10])
    }

    @Test
    fun `tremolo filter modulates amplitude`() {
        val downstream = RecordingFilter()
        val tremolo = TremoloFilter(downstream, sampleRate = 1000, frequency = 10.0, depth = 0.8f)
        val input = arrayOf(FloatArray(100) { 1.0f })
        tremolo.process(input, 0, 100)

        assertEquals(1, downstream.processedFrames)
        val out = downstream.lastInput!![0]
        assertTrue(out.any { it < 0.9f })
        assertTrue(out.any { it > 0.95f })
    }

    @Test
    fun `underwater filter attenuates sharp transients`() {
        val downstream = RecordingFilter()
        val underwater = UnderwaterFilter(downstream, sampleRate = 48000, cutoffHz = 400.0)
        val input = arrayOf(FloatArray(100) { if (it % 2 == 0) 1.0f else -1.0f }) // high frequency oscillation
        underwater.process(input, 0, 100)

        assertEquals(1, downstream.processedFrames)
        val out = downstream.lastInput!![0]
        // 24kHz oscillation should be heavily attenuated by 400Hz low-pass
        assertTrue(out.takeLast(20).all { Math.abs(it) < 0.2f })
    }

    @Test
    fun `presets are accessible by name and id`() {
        assertEquals(EqPreset.BASS, EqPreset.fromId("bass"))
        assertEquals(EqPreset.BASS, EqPreset.fromId("BASS"))
        assertEquals(EqPreset.POP, EqPreset.fromId("pop"))
        assertEquals(EqPreset.TREBLE, EqPreset.fromId("treb"))
        assertEquals(EqPreset.TREBLE, EqPreset.fromId("TREBLE"))
        assertNull(EqPreset.fromId("unknown"))
    }
}

