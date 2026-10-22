package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MapPaletteLookupTest {

    @Test
    fun `lookup returns valid byte values for primary colors`() {
        val black = MapPaletteLookup.lookup(0, 0, 0)
        assertEquals(MapPaletteLookup.BLACK_COLOR_BYTE, black)

        val white = MapPaletteLookup.lookup(255, 255, 255)
        assertNotEquals(black, white)

        val red = MapPaletteLookup.lookup(255, 0, 0)
        val green = MapPaletteLookup.lookup(0, 255, 0)
        val blue = MapPaletteLookup.lookup(0, 0, 255)

        assertNotNull(red)
        assertNotNull(green)
        assertNotNull(blue)
    }

    @Test
    fun `fillBlank correctly fills buffer with black map color`() {
        val buffer = ByteArray(16384)
        MapPaletteLookup.fillBlank(buffer)

        for (b in buffer) {
            assertEquals(MapPaletteLookup.BLACK_COLOR_BYTE, b)
        }
    }
}

