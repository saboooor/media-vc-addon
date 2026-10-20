package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ScreenMapViewersTest {
    @Test fun `only displayed maps for current viewers suppress ordinary map updates`() {
        val viewer = UUID.randomUUID()
        val bystander = UUID.randomUUID()
        val maps = ScreenMapViewers(listOf(10, 11))
        assertFalse(maps.owns(viewer, 10))
        maps.update(listOf(viewer))
        assertTrue(maps.owns(viewer, 10))
        assertTrue(maps.owns(viewer, 11))
        assertFalse(maps.owns(viewer, 12))
        assertFalse(maps.owns(bystander, 10))
        maps.update(listOf(bystander))
        assertFalse(maps.owns(viewer, 10))
        assertTrue(maps.owns(bystander, 10))
        maps.update(emptyList())
        assertFalse(maps.owns(bystander, 10))
    }

    @Test fun `caller mutations cannot retain ownership or add unrelated maps`() {
        val viewer = UUID.randomUUID()
        val ids = mutableListOf(10)
        val viewers = mutableListOf(viewer)
        val maps = ScreenMapViewers(ids)
        maps.update(viewers)
        ids.add(11)
        viewers.clear()
        assertTrue(maps.owns(viewer, 10))
        assertFalse(maps.owns(viewer, 11))
    }
}
