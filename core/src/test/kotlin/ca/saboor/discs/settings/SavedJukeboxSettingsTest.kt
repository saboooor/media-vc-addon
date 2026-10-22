package ca.saboor.discs.settings

import ca.saboor.discs.event.HologramOffset
import ca.saboor.discs.event.JukeboxBassLamps
import ca.saboor.discs.visualizer.VisualizerFacing
import ca.saboor.discs.visualizer.VisualizerInstance
import ca.saboor.discs.visualizer.VisualizerInstances
import ca.saboor.discs.visualizer.VisualizerKind
import ca.saboor.discs.visualizer.VisualizerLayout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SavedJukeboxSettingsTest {
    @Test fun `light falloff survives saving and rejects invalid values`() {
        for (falloff in listOf(0.0, 0.2, 0.8, 1.0)) {
            val settings = SavedJukeboxSettings(lightFalloff = falloff)
            assertEquals(falloff, SavedJukeboxSettings.decode(settings.encode())!!.lightFalloff)
        }
        assertNull(SavedJukeboxSettings.decode("{}")!!.lightFalloff)
        for (invalid in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                SavedJukeboxSettings(lightFalloff = invalid).encode()
            }
        }
    }

    @Test fun `hologram visibility persists independently and legacy settings keep displays enabled`() {
        for (main in listOf(false, true)) for (queue in listOf(false, true)) {
            val settings = SavedJukeboxSettings(hologramVisibility = ca.saboor.discs.event.HologramVisibility(main, queue))
            assertEquals(settings, SavedJukeboxSettings.decode(settings.encode()))
        }
        assertEquals(ca.saboor.discs.event.HologramVisibility(), SavedJukeboxSettings.decode("{}")!!.hologramVisibility)
    }
    @Test fun `saved payload preserves placement playback and available links`() {
        val settings = SavedJukeboxSettings(filterConfig = ca.saboor.discs.audio.AudioFilterConfig(tempo = 1.1, pitchSemitones = -2.0), volume = 70, autoMix = true, loop = true, shuffle = true, stereo = 2, crossfade = 0, lastCrossfade = 8,
            hologram = HologramOffset(1.5, 2.0, -0.5), queueHologram = HologramOffset(-2.0, 3.0, 1.0),
            visualizers = listOf(VisualizerInstance(7, VisualizerKind.SPEAKER, true,
                VisualizerLayout(1f, 2f, 3f, 1.5f, 0.75f), VisualizerFacing.WEST)),
            lamps = listOf(JukeboxBassLamps.Position("00000000-0000-0000-0000-000000000001", 12, 64, 16)),
            screenName = "screen-1",
            noteBlock = JukeboxBassLamps.Position("00000000-0000-0000-0000-000000000001", 20, 64, 16))
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

    @Test fun `old settings default to jukebox playback without stereo or looping`() {
        val restored = SavedJukeboxSettings.decode("{}")!!
        assertEquals(HologramOffset(), restored.queueHologram)
        assertNull(restored.noteBlock)
        assertFalse(restored.loop)
        assertFalse(restored.shuffle)
        assertEquals(0, restored.stereo)
        assertThrows(IllegalArgumentException::class.java) { SavedJukeboxSettings(stereo = 4).encode() }
    }

    @Test fun `two note block stereo links survive saving`() {
        val left = JukeboxBassLamps.Position("00000000-0000-0000-0000-000000000001", 1, 64, 0)
        val right = left.copy(x = -1)
        val settings = SavedJukeboxSettings(stereo = 3, leftNoteBlock = left, rightNoteBlock = right)
        assertEquals(settings, SavedJukeboxSettings.decode(settings.encode()))
        assertNull(SavedJukeboxSettings.decode("{}")!!.leftNoteBlock)
        assertNull(SavedJukeboxSettings.decode("{}")!!.rightNoteBlock)
    }

    @Test fun `restored visualizers keep IDs and new instances receive fresh IDs`() {
        val instances = VisualizerInstances()
        instances.restore(listOf(VisualizerInstance(7), VisualizerInstance(12, VisualizerKind.SPEAKER)))
        assertEquals(13, instances.add(VisualizerKind.BARS))
        assertEquals(listOf(7, 12, 13), instances.snapshot.map { it.id })
    }

    @Test fun `filter config survives saving and decoding`() {
        val config = ca.saboor.discs.audio.AudioFilterConfig(eqPreset = "bass", rotation = true, echo = true, speed = 1.25)
        val settings = SavedJukeboxSettings(filterConfig = config)
        val decoded = SavedJukeboxSettings.decode(settings.encode())!!
        assertEquals(config, decoded.filterConfig)
        assertTrue(SavedJukeboxSettings.decode("{}")!!.filterConfig.isEmpty())
    }
}

