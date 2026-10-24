package ca.saboor.discs.screen

import org.bukkit.map.MapPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PerceptualColorTest {
    @Test fun `sRGB conversion agrees with Oklab reference values`() {
        val black = PerceptualColor.fromRgb(0, 0, 0)
        assertEquals(0.0, black.lightness, 1e-7)
        val white = PerceptualColor.fromRgb(255, 255, 255)
        assertEquals(1.0, white.lightness, 1e-6)
        assertEquals(0.0, white.a, 1e-6)
        assertEquals(0.0, white.b, 1e-6)
        val red = PerceptualColor.fromRgb(255, 0, 0)
        assertEquals(0.62795536, red.lightness, 1e-6)
        assertEquals(0.22486306, red.a, 1e-6)
        assertEquals(0.12584630, red.b, 1e-6)
    }

    @Suppress("DEPRECATION")
    @Test fun `lookup reduces average perceptual error compared with previous five bit matching`() {
        var previousError = 0.0
        var improvedError = 0.0
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val target = PerceptualColor.fromRgb(r, g, b)
            val old = MapPalette.getColor(MapPalette.matchColor((r ushr 3) * 255 / 31,
                (g ushr 3) * 255 / 31, (b ushr 3) * 255 / 31))
            val updated = MapPalette.getColor(MapPaletteLookup.lookup(r, g, b))
            previousError += target.distanceSquared(PerceptualColor.fromRgb(old.red, old.green, old.blue))
            improvedError += target.distanceSquared(PerceptualColor.fromRgb(updated.red, updated.green, updated.blue))
            assertTrue(updated.alpha > 0)
        }
        assertTrue(improvedError < previousError, "Old error: $previousError; new error: $improvedError")
    }
}
