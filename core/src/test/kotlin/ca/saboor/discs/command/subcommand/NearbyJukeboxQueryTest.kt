package ca.saboor.discs.command.subcommand

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NearbyJukeboxQueryTest {
    @Test fun `hidden jukeboxes above and below the player are included within the sphere`() {
        val query = NearbyJukeboxQuery(0.5, 64.5, 0.5, 64)
        assertTrue(query.contains(0, 0, 0))
        assertTrue(query.contains(0, 128, 0))
        assertTrue(query.contains(64, 64, 0))
        assertFalse(query.contains(65, 64, 0))
        assertFalse(query.contains(64, 64, 64))
        assertEquals(4096.0, query.distanceSquared(0, 0, 0))
    }
    @Test fun `negative world coordinates search every intersecting chunk`() {
        val query = NearbyJukeboxQuery(-0.5, 64.0, -16.5, 16)
        assertEquals(-2, query.minChunkX)
        assertEquals(0, query.maxChunkX)
        assertEquals(-3, query.minChunkZ)
        assertEquals(-1, query.maxChunkZ)
        assertTrue(query.contains(-16, 64, -17))
    }
    @Test fun `radius bounds reject unbounded scans`() {
        assertThrows(IllegalArgumentException::class.java) { NearbyJukeboxQuery(0.0, 0.0, 0.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { NearbyJukeboxQuery(0.0, 0.0, 0.0, 257) }
    }
}
