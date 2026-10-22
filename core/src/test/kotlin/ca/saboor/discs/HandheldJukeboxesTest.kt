package ca.saboor.discs

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
        repeat(18) { assertTrue(device.enqueue(HandheldJukeboxes.Song("track-$it", "$it"))) }
        assertFalse(device.enqueue(HandheldJukeboxes.Song("overflow", "Overflow")))
        assertEquals("track-17", device.queue.last().identifier)
        assertTrue(device.advance())
        assertEquals(1, device.selected)
    }
    @Test fun `loop wraps and shuffle chooses another entry`() {
        val device = HandheldJukeboxes.Device(loop = true)
        device.enqueue(HandheldJukeboxes.Song("a", "A"))
        assertTrue(device.advance())
        assertEquals(0, device.selected)
        device.enqueue(HandheldJukeboxes.Song("b", "B"))
        device.shuffle = true
        assertTrue(device.advance())
        assertEquals(1, device.selected)
    }
    @Test fun `shuffle without loop finishes each song once`() {
        val device = HandheldJukeboxes.Device(shuffle = true)
        repeat(5) { device.enqueue(HandheldJukeboxes.Song("$it", "$it")) }
        val visited = mutableSetOf(device.selected)
        while (device.advance()) assertTrue(visited.add(device.selected))
        assertEquals(5, visited.size)
        assertEquals(0, device.selected)
    }
    @Test fun `moving physical discs preserves the current selection and stored item data`() {
        val first = HandheldJukeboxes.Song("a", "A", "encoded-original-a", 0)
        val current = HandheldJukeboxes.Song("b", "B", "encoded-original-b", 2)
        val device = HandheldJukeboxes.Device(queue = mutableListOf(first, current), selected = 1)
        device.replaceQueue(listOf(current.copy(slot = 0), first.copy(slot = 5)))
        assertEquals(0, device.selected)
        assertEquals("encoded-original-b", device.queue.first().itemData)
        assertEquals(5, device.queue.last().slot)
        val restored = Gson().fromJson(Gson().toJson(device), HandheldJukeboxes.Device::class.java)
        assertEquals(device, restored)
    }

    @Test fun `removing physical entries does not resurrect them on save or advance`() {
        val disc = HandheldJukeboxes.Song("a", "A", "encoded-disc", 3)
        val device = HandheldJukeboxes.Device(queue = mutableListOf(disc))
        device.replaceQueue(emptyList())
        assertTrue(device.queue.isEmpty())
        assertFalse(device.advance())
        assertEquals(0, device.selected)
        assertTrue(Gson().fromJson(Gson().toJson(device), HandheldJukeboxes.Device::class.java).queue.isEmpty())
    }
}
