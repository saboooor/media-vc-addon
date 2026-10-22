package ca.saboor.discs.audio

import kotlin.math.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter

class PassFilterTest {
    private val sink = object : FloatPcmAudioFilter {
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {}
        override fun seekPerformed(requested: Long, provided: Long) {}
        override fun flush() {}
        override fun close() {}
    }
    private fun energy(highPass: Boolean, hz: Double): Double {
        val samples = FloatArray(9600) { (0.5 * sin(2 * PI * hz * it / 48000)).toFloat() }
        PassFilter(sink, 1, 48000, highPass) { 1000.0 }.process(arrayOf(samples), 0, samples.size)
        assertTrue(samples.all { it.isFinite() })
        return sqrt(samples.drop(4800).map { it.toDouble().pow(2) }.average())
    }
    @Test fun `low and high pass select opposite frequency ranges`() {
        assertTrue(energy(false, 100.0) > 0.3)
        assertTrue(energy(false, 8000.0) < 0.02)
        assertTrue(energy(true, 100.0) < 0.02)
        assertTrue(energy(true, 8000.0) > 0.3)
    }
    @Test fun `zero cutoff bypasses and filter settings survive saved payloads`() {
        val samples = floatArrayOf(0.1f, -0.2f, 0.5f)
        val expected = samples.copyOf()
        PassFilter(sink, 1, 48000, false) { 0.0 }.process(arrayOf(samples), 0, samples.size)
        assertArrayEquals(expected, samples)
        val config = AudioFilterConfig(lowPassHz = 6000.0, highPassHz = 150.0)
        assertFalse(config.isEmpty())
        assertNotEquals(AudioFilterConfig(), config)
        val settings = ca.saboor.discs.settings.SavedJukeboxSettings(filterConfig = config)
        assertEquals(settings, ca.saboor.discs.settings.SavedJukeboxSettings.decode(settings.encode()))
    }
    @Test fun `automix sweeps open incoming highs and close outgoing highs only on matched blends`() {
        val envelope = MixEnvelope(10000, beatMatched = true)
        assertEquals(0.0 to 800.0, envelope.passCutoffs(0))
        assertEquals(0.0, envelope.passCutoffs(6000).second)
        assertEquals(1200.0, envelope.passCutoffs(10000).first, 1e-8)
        assertEquals(0.0 to 0.0, MixEnvelope(4000).passCutoffs(2000))
    }
}
