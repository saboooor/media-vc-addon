package su.plo.voice.discs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.discs.visualizer.LightBand
import su.plo.voice.discs.visualizer.SpeakerDriver
import su.plo.voice.discs.settings.SavedJukeboxSettings
import su.plo.voice.discs.event.JukeboxBassLamps.Position

class LightBandTest {
    @Test fun `each spectrum bin drives only its assigned band`() {
        for (index in 0 until 16) {
            val levels = FloatArray(16).also { it[index] = 0.8f }
            val expected = when (index) { in 0..4 -> LightBand.BASS; in 5..10 -> LightBand.MID; else -> LightBand.HIGHS }
            LightBand.entries.forEach { assertEquals(if (it == expected) 0.8f else 0f, it.level(levels)) }
        }
        LightBand.entries.forEach { assertEquals(0f, it.level(floatArrayOf())) }
    }

    @Test fun `saved light bands survive item settings and old settings default to bass`() {
        val settings = SavedJukeboxSettings(
            lamps = listOf(Position("00000000-0000-0000-0000-000000000001", 1, 64, 2)),
            lampBands = listOf(LightBand.HIGHS))
        assertEquals(settings, SavedJukeboxSettings.decode(settings.encode()))
        val old = settings.encode().replace(Regex(",?\\\"lampBands\\\":\\[[^]]*]"), "")
        assertTrue(SavedJukeboxSettings.decode(old)!!.lampBands.isEmpty())
    }

    @Test fun `square driver uses three layers with a stationary surround`() {
        assertEquals(3, SpeakerDriver.parts.size)
        assertFalse(SpeakerDriver.parts.first().moving)
        assertTrue(SpeakerDriver.parts.drop(1).all { it.moving })
        SpeakerDriver.parts.forEach { assertEquals(it.width, it.height) }
        assertTrue(SpeakerDriver.excursion(1f) > SpeakerDriver.excursion(0f))
    }
}
