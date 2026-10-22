package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RgbTileCacheTest {
    @Test fun `reused frame buffers only invalidate the tile whose RGB pixels changed`() {
        val frame = ByteArray(256 * 256 * 3)
        val tiles = Array(4) { RgbTileCache() }
        fun update() = tiles.mapIndexed { i, tile -> tile.update(frame, 256, i % 2 * 128, i / 2 * 128) }
        assertEquals(listOf(true, true, true, true), update())
        assertEquals(listOf(false, false, false, false), update())
        frame[(255 * 256 + 255) * 3 + 2] = 1
        assertEquals(listOf(false, false, false, true), update())
        assertEquals(listOf(false, false, false, false), update())
        frame[0] = 1
        assertEquals(listOf(true, false, false, false), update())
    }
}
