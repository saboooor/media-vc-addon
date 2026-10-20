package su.plo.voice.discs.screen

import kotlin.math.pow

/** Oklab conversion from https://bottosson.github.io/posts/oklab/ (linear sRGB input). */
internal data class PerceptualColor(val lightness: Double, val a: Double, val b: Double) {
    fun distanceSquared(other: PerceptualColor): Double {
        val dl = lightness - other.lightness
        val da = a - other.a
        val db = b - other.b
        return dl * dl + da * da + db * db
    }

    companion object {
        private val linear = DoubleArray(256) {
            val value = it / 255.0
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }

        fun fromRgb(r: Int, g: Int, b: Int): PerceptualColor {
            val red = linear[r]
            val green = linear[g]
            val blue = linear[b]
            val l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
            val m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
            val s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
            return PerceptualColor(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            )
        }
    }
}
