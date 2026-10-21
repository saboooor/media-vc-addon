package su.plo.voice.discs

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HandheldJukeboxesTest {
    @Test fun `devices have independent identities and queues`() {
        val first = HandheldJukeboxes.Device()
        val second = HandheldJukeboxes.Device()
        first.enqueue(HandheldJukeboxes.Song("track-a", "A"))
        assertNotEquals(first.id, second.id)
        assertTrue(second.queue.isEmpty())
    }

    @Test fun `queue volume and selection survive item serialization`() {
        val device = HandheldJukeboxes.Device(volume = 40)
        device.enqueue(HandheldJukeboxes.Song("track-a", "A"))
        device.enqueue(HandheldJukeboxes.Song("track-b", "B"))
        device.selected = 1
        val restored = Gson().fromJson(Gson().toJson(device), HandheldJukeboxes.Device::class.java)
        assertEquals(device, restored)
        assertFalse(restored.advance())
        assertEquals(0, restored.selected)
        assertEquals(2, restored.queue.size)
    }

    @Test fun `queue stops at capacity without overwriting tracks`() {
        val device = HandheldJukeboxes.Device()
        repeat(45) { assertTrue(device.enqueue(HandheldJukeboxes.Song("track-$it", "$it"))) }
        assertFalse(device.enqueue(HandheldJukeboxes.Song("overflow", "Overflow")))
        assertEquals("track-44", device.queue.last().identifier)
        assertTrue(device.advance())
        assertEquals(1, device.selected)
    }
}
