package ca.saboor.discs

import ca.saboor.discs.event.JukeboxBassLamps.Link
import ca.saboor.discs.event.JukeboxBassLamps.Position
import ca.saboor.discs.event.LightEffectMaskSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerLightEffectsTest {
    @Test fun `beacon mask includes shutter across section boundary and isolates worlds`() {
        val lamp = Position("world", -1, 15, -17)
        val snapshot = LightEffectMaskSnapshot(listOf(Link(lamp, lamp, originalData = "minecraft:beacon", shutterData = "minecraft:glass")))
        assertEquals("minecraft:beacon", snapshot.blocks[lamp])
        assertEquals("minecraft:glass", snapshot.blocks[lamp.copy(y = 16)])
        assertEquals(2, snapshot.chunk("world", -1, -2).size)
        assertTrue(snapshot.chunk("other", -1, -2).isEmpty())
        assertTrue(snapshot.freezesLighting("world", 0, -1), "Light spills into neighboring chunks")
        assertFalse(snapshot.freezesLighting("world", 1, -2))
        assertFalse(snapshot.freezesLighting("other", -1, -2))
    }

    @Test fun `unlinking removes masks while old immutable snapshot remains valid`() {
        val lamp = Position("world", 0, -64, 0)
        val links = mutableListOf(Link(lamp, lamp, originalData = "minecraft:light[level=12]", theatre = true))
        val before = LightEffectMaskSnapshot(links)
        links.clear()
        val after = LightEffectMaskSnapshot(links)
        assertEquals("minecraft:light[level=12]", before.blocks[lamp])
        assertTrue(after.blocks.isEmpty())
        assertFalse(after.freezesLighting("world", 0, 0))
    }
}
