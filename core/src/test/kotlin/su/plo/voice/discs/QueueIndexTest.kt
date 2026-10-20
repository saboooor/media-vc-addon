package su.plo.voice.discs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import su.plo.voice.discs.command.subcommand.remapQueueIndex

class QueueIndexTest {
    @Test fun `moving and adding discs preserves the current song`() {
        assertEquals(2, remapQueueIndex(listOf("a", "b", "c"), listOf("new", "c", "b", "a"), 1, "b"))
    }

    @Test fun `removing current disc leaves it outside the queue`() {
        assertEquals(-1, remapQueueIndex(listOf("a", "b", "c"), listOf("a", "c"), 1, "b"))
        assertEquals(-1, remapQueueIndex(listOf("a"), emptyList(), 0, "a"))
    }

    @Test fun `identical discs preserve their occurrence`() {
        assertEquals(3, remapQueueIndex(listOf("a", "b", "a"), listOf("new", "a", "b", "a"), 2, "a"))
        assertEquals(-1, remapQueueIndex(listOf("a", "a"), listOf("a"), 1, "a"))
    }

    @Test fun `reinserted current disc rejoins queue`() {
        assertEquals(1, remapQueueIndex(listOf("b"), listOf("b", "a"), -1, "a"))
    }
}
