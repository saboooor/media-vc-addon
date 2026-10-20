package su.plo.voice.discs.visualizer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class SpectrumAnalyzerTest {
    @Test fun `silence stays flat and reset clears the spectrum`() {
        val analyzer = SpectrumAnalyzer()
        analyzer.accept(arrayOf(FloatArray(4800)), 0, 4800, 48000)
        assertTrue(analyzer.levels.all { it == 0f })
        analyzer.reset()
        assertEquals(16, analyzer.levels.size)
        assertTrue(analyzer.levels.all { it == 0f })
    }

    @Test fun `tone energy lands in the corresponding frequency band`() {
        val analyzer = SpectrumAnalyzer()
        val samples = FloatArray(4800) { (sin(2 * PI * 1000 * it / 48000) * 0.5).toFloat() }
        analyzer.accept(arrayOf(samples, samples), 0, samples.size, 48000)
        val peak = analyzer.levels.indices.maxBy { analyzer.levels[it] }
        assertTrue(peak in 7..9, "1 kHz peak was in band $peak")
        assertTrue(analyzer.levels.all { it.isFinite() && it in 0f..1f })
        analyzer.reset()
        assertTrue(analyzer.levels.all { it == 0f })
    }

    @Test fun `analysis is throttled to twenty updates per second`() {
        val analyzer = SpectrumAnalyzer()
        val initial = analyzer.levels
        analyzer.accept(arrayOf(FloatArray(2399) { 0.5f }), 0, 2399, 48000)
        assertSame(initial, analyzer.levels)
        analyzer.accept(arrayOf(floatArrayOf(0.5f)), 0, 1, 48000)
        assertNotSame(initial, analyzer.levels)
    }
    @Test fun `bars distinguish quieter and louder tones and release to silence`() {
        val analyzer = SpectrumAnalyzer()
        fun feed(amplitude: Double) {
            val audio = FloatArray(2400) { (sin(2 * PI * 1000 * it / 48000) * amplitude).toFloat() }
            analyzer.accept(arrayOf(audio), 0, audio.size, 48000)
        }
        feed(0.02)
        val quiet = analyzer.levels.max()
        feed(0.5)
        assertTrue(quiet > 0f)
        assertTrue(analyzer.levels.max() > quiet)
        feed(0.0)
        assertTrue(analyzer.levels.all { it == 0f })
    }

    @Test fun `working buffer reuse does not mutate previously published levels`() {
        val analyzer = SpectrumAnalyzer()
        val audio = FloatArray(2400) { sin(2 * PI * 1000 * it / 48000).toFloat() }
        analyzer.accept(arrayOf(audio), 0, audio.size, 48000)
        val published = analyzer.levels
        val snapshot = published.copyOf()
        analyzer.accept(arrayOf(FloatArray(2400)), 0, 2400, 48000)
        assertArrayEquals(snapshot, published)
        assertTrue(analyzer.levels.all { it == 0f })
    }

    @Test fun `frequency bands update when the source sample rate changes`() {
        val analyzer = SpectrumAnalyzer()
        for (rate in listOf(48000, 32000, 44100, 48000)) {
            val audio = FloatArray(rate / 20) { (sin(2 * PI * 1000 * it / rate) * 0.5).toFloat() }
            analyzer.accept(arrayOf(audio), 0, audio.size, rate)
            val peak = analyzer.levels.indices.maxBy { analyzer.levels[it] }
            assertTrue(peak in 7..9, "1 kHz band was $peak at $rate Hz")
        }
    }

}
