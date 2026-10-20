package su.plo.voice.discs.screen

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
        for (strength in listOf(12, 32, 100)) {
            val dither = OrderedMapDither(strength)
            for (y in 0..3) for (x in 0..3) {
                assertEquals(MapPaletteLookup.lookup(0, 0, 0), dither.lookup(0, 0, 0, x, y))
                assertEquals(MapPaletteLookup.lookup(255, 255, 255), dither.lookup(255, 255, 255, x, y))
                assertEquals(OrderedMapDither(strength.coerceAtMost(32)).lookup(128, 80, 200, x, y),
                    dither.lookup(128, 80, 200, x, y))
            }
        }
    }
}
