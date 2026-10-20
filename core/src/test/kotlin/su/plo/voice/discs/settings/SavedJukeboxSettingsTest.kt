package su.plo.voice.discs.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.discs.event.HologramOffset
import su.plo.voice.discs.event.JukeboxBassLamps
import su.plo.voice.discs.visualizer.*

class SavedJukeboxSettingsTest {
    @Test fun `saved payload preserves placement playback and available links`() {
        val settings = SavedJukeboxSettings(volume = 70, crossfade = 0, lastCrossfade = 8,
            hologram = HologramOffset(1.5, 2.0, -0.5),
            visualizers = listOf(VisualizerInstance(7, VisualizerKind.SPEAKER, true,
                VisualizerLayout(1f, 2f, 3f, 1.5f, 0.75f), VisualizerFacing.WEST)),
            lamps = listOf(JukeboxBassLamps.Position("00000000-0000-0000-0000-000000000001", 12, 64, 16)),
            screenName = "screen-1")
        assertEquals(settings, SavedJukeboxSettings.decode(settings.encode()))
    }

    @Test fun `malformed unsupported and out of range data is ignored`() {
        assertNull(SavedJukeboxSettings.decode("invalid JSON"))
        assertNull(SavedJukeboxSettings.decode("null"))
        assertNull(SavedJukeboxSettings.decode(SavedJukeboxSettings().encode().replace("\"version\":1", "\"version\":99")))
        assertNull(SavedJukeboxSettings.decode(SavedJukeboxSettings().encode().replace("\"volume\":100", "\"volume\":999")))
        assertThrows(IllegalArgumentException::class.java) {
            SavedJukeboxSettings(visualizers = listOf(VisualizerInstance(1), VisualizerInstance(1))).encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedJukeboxSettings(hologram = HologramOffset(Double.NaN, 0.0, 0.0)).encode()
        }
    }

    @Test fun `restored visualizers keep IDs and new instances receive fresh IDs`() {
        val instances = VisualizerInstances()
        instances.restore(listOf(VisualizerInstance(7), VisualizerInstance(12, VisualizerKind.SPEAKER)))
        assertEquals(13, instances.add(VisualizerKind.BARS))
        assertEquals(listOf(7, 12, 13), instances.snapshot.map { it.id })
    }
}
