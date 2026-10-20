package su.plo.voice.discs.visualizer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisualizerInstancesTest {
    @Test fun `instances retain independent type layout facing and enabled state`() {
        val collection = VisualizerInstances()
        val speaker = collection.add(VisualizerKind.SPEAKER)!!
        collection.update(speaker) { it.copy(facing = VisualizerFacing.EAST, layout = it.layout.adjust(dy = 1f)) }
        assertEquals(VisualizerInstance(1), collection.snapshot.first())
        val edited = collection.snapshot.last()
        assertEquals(VisualizerKind.SPEAKER, edited.kind)
        assertEquals(VisualizerFacing.EAST, edited.facing)
        assertEquals(1f, edited.layout.y)
        assertTrue(edited.enabled)
    }

    @Test fun `deletion never reuses a stale dialog identifier and empty collections can add again`() {
        val collection = VisualizerInstances()
        val old = collection.add(VisualizerKind.BARS)!!
        collection.remove(old)
        val fresh = collection.add(VisualizerKind.SPEAKER)!!
        assertNotEquals(old, fresh)
        collection.update(old) { it.copy(enabled = false) }
        assertTrue(collection.snapshot.last().enabled)
        collection.snapshot.forEach { collection.remove(it.id) }
        assertTrue(collection.snapshot.isEmpty())
        assertNotNull(collection.add(VisualizerKind.SPEAKER))
    }

    @Test fun `unchanged edits retain the published snapshot and changed edits preserve old readers`() {
        val collection = VisualizerInstances()
        val original = collection.snapshot
        collection.update(1) { it.copy(layout = it.layout.adjust(dx = 0f)) }
        assertSame(original, collection.snapshot)
        collection.update(999) { error("Unknown IDs must not invoke the edit") }
        assertSame(original, collection.snapshot)
        collection.update(1) { it.copy(enabled = true) }
        assertNotSame(original, collection.snapshot)
        assertFalse(original.first().enabled)
        assertTrue(collection.snapshot.first().enabled)
    }

    @Test fun `instance count is bounded`() {
        val collection = VisualizerInstances()
        repeat(7) { assertNotNull(collection.add(VisualizerKind.BARS)) }
        assertNull(collection.add(VisualizerKind.SPEAKER))
        assertEquals(8, collection.snapshot.size)
    }

    @Test fun `speaker reacts to bass attacks and settles after the beat`() {
        val speaker = SpeakerEnvelope()
        assertEquals(0f, speaker.update(0f))
        val attack = speaker.update(0.8f)
        val sustained = speaker.update(0.8f)
        assertTrue(attack > sustained)
        var released = sustained
        repeat(20) { released = speaker.update(0f) }
        assertTrue(released < 0.01f)
        assertEquals(1f, speaker.update(1f))
    }
    @Test fun `bass lamps switch on for a hit and off after silence`() {
        val lamp = BassLampEnvelope()
        assertFalse(lamp.update(0f))
        assertTrue(lamp.update(1f))
        assertTrue(lamp.update(1f))
        repeat(20) { lamp.update(0f) }
        assertFalse(lamp.lit)
    }


}
