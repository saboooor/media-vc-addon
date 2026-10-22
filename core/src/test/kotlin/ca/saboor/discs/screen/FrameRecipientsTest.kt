package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameRecipientsTest {
    @Test fun `identical frames skip existing viewers but reach arrivals and returning viewers`() {
        val routing = FrameRecipients<String>(100)
        val frame = byteArrayOf(1)
        assertEquals(listOf("a"), routing.select(frame, listOf("a"), 0))
        assertTrue(routing.select(frame, listOf("a"), 1).isEmpty())
        assertEquals(listOf("b"), routing.select(frame, listOf("a", "b"), 2))
        assertTrue(routing.select(frame, listOf("b"), 3).isEmpty())
        assertEquals(listOf("a"), routing.select(frame, listOf("a", "b"), 4))
        assertEquals(listOf("a", "b"), routing.select(frame, listOf("a", "b"), 100))
        assertTrue(routing.select(frame, listOf("a", "b"), 101).isEmpty())
    }
    @Test fun `changed frames and cleared playback refresh every viewer`() {
        val routing = FrameRecipients<String>(100)
        routing.select(byteArrayOf(1), listOf("a"), 0)
        val changed = byteArrayOf(2)
        assertEquals(listOf("a"), routing.select(changed, listOf("a"), 1))
        routing.clear()
        assertEquals(listOf("a"), routing.select(changed, listOf("a"), 2))
    }
    @Test fun `reconnecting sessions with equal player IDs receive the current frame immediately`() {
        data class Session(val id: String)
        val routing = FrameRecipients<Session>(100)
        val frame = byteArrayOf(1)
        routing.select(frame, listOf(Session("same-player")), 0)
        val reconnected = Session("same-player")
        assertSame(reconnected, routing.select(frame, listOf(reconnected), 1).single())
    }
}
