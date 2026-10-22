package ca.saboor.discs

import ca.saboor.discs.event.JukeboxBassLamps.Position
import ca.saboor.discs.settings.SavedJukeboxSettings
import ca.saboor.discs.visualizer.LightBand
import ca.saboor.discs.visualizer.SpeakerDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LightBandTest {
    @Test fun `each spectrum bin drives only its assigned band`() {
        for (index in 0 until 16) {
            val levels = FloatArray(16).also { it[index] = 0.8f }
            val expected = when (index) { in 0..4 -> LightBand.BASS; in 5..10 -> LightBand.MID; else -> LightBand.HIGHS }
            listOf(LightBand.BASS, LightBand.MID, LightBand.HIGHS).forEach {
                assertEquals(if (it == expected) 0.8f else 0f, it.level(levels))
            }
        }
        LightBand.entries.forEach { assertEquals(0f, it.level(floatArrayOf())) }
    }

    @Test fun `additional responses select their own ranges and persist`() {
        val ranges = mapOf(LightBand.DEEP_BASS to 0..2, LightBand.LOW_MIDS to 4..6,
            LightBand.VOCALS to 6..9, LightBand.SPARKLE to 13..15, LightBand.FULL to 0..15)
        for ((band, range) in ranges) {
            for (index in 0 until 16) {
                val levels = FloatArray(16).also { it[index] = 0.8f }
                assertEquals(if (index in range) 0.8f else 0f, band.level(levels), "$band bin $index")
            }
            val settings = SavedJukeboxSettings(lamps = listOf(Position("00000000-0000-0000-0000-000000000001", 1, 64, 2)), lampBands = listOf(band))
            assertEquals(settings, SavedJukeboxSettings.decode(settings.encode()))
        }
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
