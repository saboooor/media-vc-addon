package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OrderedMapDitherTest {
    @Test fun `zero strength matches original conversion`() {
        val dither = OrderedMapDither(0)
        for (value in 0..255) for (y in 0..3) for (x in 0..3) {
            assertEquals(MapPaletteLookup.lookup(value, 255 - value, value / 2),
                dither.lookup(value, 255 - value, value / 2, x, y))
        }
    }

    @Test fun `pattern repeats across tiles and is stable between frames`() {
        val dither = OrderedMapDither(12)
        var mixedGrays = 0
        for (value in 16..239) {
            val colors = mutableSetOf<Byte>()
            for (y in 0..3) for (x in 0..3) {
                val color = dither.lookup(value, value, value, x, y)
                assertEquals(color, dither.lookup(value, value, value, x + 128, y + 128))
                assertEquals(color, dither.lookup(value, value, value, x, y))
                assertTrue((color.toInt() and 255) >= 4, "Map pixels must remain opaque")
                colors += color
            }
            if (colors.size > 1) mixedGrays++
        }
        assertTrue(mixedGrays > 0, "Dithering should mix palette colors in gray gradients")
    }

    @Test fun `black and white remain solid and strengths are bounded`() {
        for (mode in DitherMode.entries) {
            for (strength in listOf(12, 32, 100)) {
                val dither = OrderedMapDither(strength, mode)
                for (y in 0..3) for (x in 0..3) {
                    assertEquals(MapPaletteLookup.lookup(0, 0, 0), dither.lookup(0, 0, 0, x, y))
                    assertEquals(MapPaletteLookup.lookup(255, 255, 255), dither.lookup(255, 255, 255, x, y))
                    assertEquals(OrderedMapDither(strength.coerceAtMost(32), mode).lookup(128, 80, 200, x, y),
                        dither.lookup(128, 80, 200, x, y))
                }
            }
        }
    }

    @Test fun `ign mode mixes gradients without 4x4 periodic grid repetition`() {
        val dither = OrderedMapDither(12, DitherMode.IGN)
        var mixedGrays = 0
        var nonRepeating4x4 = 0
        for (value in 16..239) {
            val colors = mutableSetOf<Byte>()
            for (y in 0..7) for (x in 0..7) {
                val color = dither.lookup(value, value, value, x, y)
                assertEquals(color, dither.lookup(value, value, value, x, y))
                assertEquals(color, dither.lookup(value, value, value, x + 128, y + 128))
                assertTrue((color.toInt() and 255) >= 4, "Map pixels must remain opaque")
                colors += color
                if (x < 4 && y < 4 && color != dither.lookup(value, value, value, x + 4, y)) {
                    nonRepeating4x4++
                }
            }
            if (colors.size > 1) mixedGrays++
        }
        assertTrue(mixedGrays > 0, "IGN dithering should mix palette colors in gray gradients")
        assertTrue(nonRepeating4x4 > 0, "IGN dithering must not repeat with a 4-pixel period")
    }

    @Test fun `distance adaptive strength uses coarse tiers and hysteresis`() {
        assertEquals(12, OrderedMapDither.adaptiveStrength(12, 0.0))
        assertEquals(12, OrderedMapDither.adaptiveStrength(12, 8.0))
        assertEquals(6, OrderedMapDither.adaptiveStrength(12, 12.0))
        assertEquals(0, OrderedMapDither.adaptiveStrength(12, 18.0))
        assertEquals(0, OrderedMapDither.adaptiveStrength(12, 40.0))
        assertEquals(0, OrderedMapDither.adaptiveStrength(0, 2.0))
        // Hysteresis keeps the previous tier across minor movement near thresholds
        assertEquals(12, OrderedMapDither.adaptiveStrength(12, 9.0, previousStrength = 12))
        assertEquals(6, OrderedMapDither.adaptiveStrength(12, 7.0, previousStrength = 6))
        assertEquals(0, OrderedMapDither.adaptiveStrength(12, 17.0, previousStrength = 0))
    }
}
