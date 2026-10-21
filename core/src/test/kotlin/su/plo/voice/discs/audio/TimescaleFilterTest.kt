package su.plo.voice.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import kotlin.math.*

class TimescaleFilterTest {
    private class Sink : FloatPcmAudioFilter {
        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            repeat(length) { left += input[0][offset + it]; right += input[1][offset + it] }
        }
        override fun flush() {}
        override fun close() {}
        override fun seekPerformed(requested: Long, provided: Long) {}
        fun frequency(): Double {
            val samples = left.subList(4800, left.size - 4800)
            val crossings = samples.indices.drop(1).count { samples[it - 1] <= 0 && samples[it] > 0 }
            return crossings * 48_000.0 / samples.size
        }
    }
    private fun render(parameters: TimescaleParameters): Sink {
        val sink = Sink()
        val filter = TimescaleFilter(sink, 2, 48_000, { parameters })
        repeat(200) { frame ->
            val samples = Array(2) { channel -> FloatArray(960) { i ->
                (0.5 * sin(2.0 * PI * 440 * (frame * 960 + i) / 48_000) * if (channel == 0) 1 else -1).toFloat()
            } }
            filter.process(samples, 0, 960)
        }
        filter.flush(); filter.close()
        return sink
    }
    @Test fun `time stretch changes duration while preserving a 440 Hz pitch and stereo phase`() {
        for (tempo in listOf(0.8, 1.25)) {
            val output = render(TimescaleParameters(tempo = tempo))
            assertEquals(4.0 / tempo, output.left.size / 48_000.0, 0.06)
            assertEquals(440.0, output.frequency(), 4.0)
            output.left.indices.forEach { assertEquals(output.left[it], -output.right[it], 0.0001f) }
        }
    }
    @Test fun `one octave transposition changes pitch without changing song duration`() {
        for ((semitones, frequency) in listOf(12.0 to 880.0, -12.0 to 220.0)) {
            val output = render(TimescaleParameters(pitchSemitones = semitones))
            assertEquals(4.0, output.left.size / 48_000.0, 0.06)
            assertEquals(frequency, output.frequency(), 5.0)
        }
    }
    @Test fun `legacy rate effects still change both pitch and duration`() {
        val output = render(TimescaleParameters(rate = 1.25))
        assertEquals(3.2, output.left.size / 48_000.0, 0.06)
        assertEquals(550.0, output.frequency(), 4.0)
    }
    @Test fun `neutral processing is exact and a seek discards buffered pre seek sound`() {
        val sink = Sink()
        var settings = TimescaleParameters()
        val filter = TimescaleFilter(sink, 2, 48_000, { settings })
        val samples = arrayOf(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(-0.1f, -0.2f, -0.3f))
        filter.process(samples, 1, 2)
        assertEquals(listOf(0.2f, 0.3f), sink.left)
        settings = TimescaleParameters(tempo = 0.92)
        filter.process(Array(2) { FloatArray(960) { 0.5f } }, 0, 960)
        filter.seekPerformed(60000, 60000)
        sink.left.clear(); sink.right.clear()
        filter.process(Array(2) { FloatArray(9600) }, 0, 9600)
        filter.flush()
        assertTrue(sink.left.all { abs(it) < 0.0001f })
        filter.close()
    }
    @Test fun `tempo changes update the running DSP without rebuilding the filter`() {
        val sink = Sink()
        var settings = TimescaleParameters(tempo = 0.94)
        val filter = TimescaleFilter(sink, 2, 48_000, { settings })
        repeat(200) { frame ->
            settings = TimescaleParameters(tempo = AutoMix.releaseTempo(0.94, frame * 20L))
            filter.process(Array(2) { FloatArray(960) { i -> (0.5 * sin(2 * PI * 440 * (frame * 960 + i) / 48000)).toFloat() } }, 0, 960)
        }
        filter.flush(); filter.close()
        assertTrue(sink.left.size in 190_000..205_000)
        assertEquals(440.0, sink.frequency(), 4.0)
        assertTrue(sink.left.all { it.isFinite() && abs(it) <= 1f })
    }
    @Test fun `media clock follows produced rates and resets on seek`() {
        val clock = TimescaleClock()
        clock.decoded(960, 1.25); clock.decoded(960, 0.8)
        assertEquals(1000L, clock.sent(1000))
        assertEquals(1025L, clock.sent(1020))
        assertEquals(1041L, clock.sent(1040))
        clock.reset(60_000)
        clock.decoded(960, 1.0)
        assertEquals(60_000L, clock.sent(60_000))
        assertEquals(60_020L, clock.sent(60_020))
    }
    @Test fun `invalid or extreme DSP controls are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TimescaleParameters(tempo = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TimescaleParameters(pitchSemitones = 100.0) }
        assertThrows(IllegalArgumentException::class.java) { TimescaleParameters(rate = 0.0) }
    }
}
