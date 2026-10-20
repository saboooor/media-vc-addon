package su.plo.voice.discs.screen

import org.bukkit.map.MapPalette
import java.util.Arrays

/** Six bits per RGB channel, matched in Oklab once; rendering uses a 256 KiB byte lookup. */
object MapPaletteLookup {
    private val quantized = IntArray(256) { (it * 63 + 127) / 255 }
    private val lookupTable = ByteArray(64 * 64 * 64)
    val BLACK_COLOR_BYTE: Byte

    init {
        // The Bukkit palette follows the server version. Skip transparent and unavailable entries.
        val palette = (4..255).mapNotNull { index ->
            val color = runCatching { MapPalette.getColor(index.toByte()) }.getOrNull()
                ?: return@mapNotNull null
            if (color.alpha == 0) null
            else index.toByte() to PerceptualColor.fromRgb(color.red, color.green, color.blue)
        }
        check(palette.isNotEmpty()) { "No opaque Minecraft map colors available" }
        for (r in 0 until 64) for (g in 0 until 64) for (b in 0 until 64) {
            val target = PerceptualColor.fromRgb((r * 255 + 31) / 63, (g * 255 + 31) / 63, (b * 255 + 31) / 63)
            var best = palette.first().first
            var bestDistance = Double.POSITIVE_INFINITY
            for ((index, color) in palette) {
                val distance = target.distanceSquared(color)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            lookupTable[(r shl 12) or (g shl 6) or b] = best
        }
        BLACK_COLOR_BYTE = lookupTable[0]
    }

    /** RGB channels are unsigned bytes, consistent with the raw FFmpeg frame buffer. */
    fun lookup(r: Int, g: Int, b: Int): Byte = lookupTable[
        (quantized[r and 255] shl 12) or (quantized[g and 255] shl 6) or quantized[b and 255]
    ]

    fun fillBlank(buffer: ByteArray, color: Byte = BLACK_COLOR_BYTE) {
        Arrays.fill(buffer, color)
    }
}
