package ca.saboor.discs.visualizer

import ca.saboor.discs.event.JukeboxBassLamps.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LightBlockFrameTest {
    @Test fun `paired lamps in a section are sent together in one immutable batch`() {
        val left = Position("world", 1, 64, 1)
        val right = Position("world", 14, 64, 1)
        val pending = mutableMapOf(left to "minecraft:pearlescent_froglight[axis=y]", right to "minecraft:pearlescent_froglight[axis=y]")
        val completed = LightBlockFrame(pending)
        pending[right] = "minecraft:black_concrete"
        assertEquals(1, completed.sections.size)
        assertEquals(setOf(left, right), completed.sections.values.single().map { it.key }.toSet())
        assertEquals(completed.blocks[left], completed.blocks[right], "Subsequent writes must not split a published frame")
    }

    @Test fun `packet sections respect negative coordinates height and world`() {
        val positions = listOf(Position("world", -1, -1, -1), Position("world", -16, -16, -16),
            Position("world", -17, -16, -1), Position("world", -1, 0, -1), Position("other", -1, -1, -1))
        val frame = LightBlockFrame(positions.associateWith { "minecraft:black_concrete" })
        assertEquals(4, frame.sections.size)
        assertEquals(2, frame.sections[LightBlockFrame.Section("world", -1, -1, -1)]?.size)
        assertEquals(1, frame.sections[LightBlockFrame.Section("other", -1, -1, -1)]?.size)
    }
}
