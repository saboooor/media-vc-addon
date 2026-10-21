package su.plo.voice.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import kotlin.math.*

class MixBassFilterTest {
    private val sink = object : FloatPcmAudioFilter {
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {}
        override fun seekPerformed(requested: Long, provided: Long) {}
        override fun flush() {}
        override fun close() {}
    }
    private fun tone(hz: Double) = FloatArray(9600) { (0.5 * sin(2 * PI * hz * it / 48000)).toFloat() }
    private fun rms(samples: FloatArray) = sqrt(samples.drop(4800).map { it.toDouble().pow(2) }.average())

    @Test fun `bass handover cuts low energy while retaining upper frequencies`() {
        val low = tone(60.0)
        val high = tone(3000.0)
        val beforeLow = rms(low)
        val beforeHigh = rms(high)
        MixBassFilter(sink, 1, 48000) { 0.0 }.process(arrayOf(low), 0, low.size)
        MixBassFilter(sink, 1, 48000) { 0.0 }.process(arrayOf(high), 0, high.size)
        assertTrue(rms(low) < beforeLow * 0.4)
        assertTrue(rms(high) > beforeHigh * 0.9)
    }

    @Test fun `full bass gain leaves samples unchanged and restoration settles smoothly`() {
        var gain = 1.0
        val filter = MixBassFilter(sink, 1, 48000) { gain }
        val input = tone(60.0)
        val original = input.copyOf()
        filter.process(arrayOf(input), 0, input.size)
        assertArrayEquals(original, input)
        gain = 0.0
        filter.process(arrayOf(tone(60.0)), 0, 9600)
        gain = 1.0
        val restored = tone(60.0)
        filter.process(arrayOf(restored), 0, restored.size)
        assertTrue(rms(restored) > rms(original) * 0.99)
        assertTrue(restored.all { it.isFinite() })
    }
}
