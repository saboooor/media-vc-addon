package su.plo.voice.discs.screen

import org.bukkit.map.MapPalette
import java.util.Arrays

/**
 * High-performance 15-bit RGB to Minecraft Map Color palette lookup table.
 * Precomputes 32,768 RGB color lookups so frame rendering does not incur any
 * runtime distance-search overhead per pixel.
 */
object MapPaletteLookup {

    // 15-bit color lookup table: 5 bits Red, 5 bits Green, 5 bits Blue = 32,768 entries
    private val lookupTable = ByteArray(32768)

    // Standard black color byte on Minecraft map canvas
    val BLACK_COLOR_BYTE: Byte

    init {
        BLACK_COLOR_BYTE = MapPalette.matchColor(0, 0, 0)
        for (r in 0 until 32) {
            val r8 = (r * 255 / 31)
            for (g in 0 until 32) {
                val g8 = (g * 255 / 31)
                for (b in 0 until 32) {
                    val b8 = (b * 255 / 31)
                    val key = (r shl 10) or (g shl 5) or b
                    lookupTable[key] = MapPalette.matchColor(r8, g8, b8)
                }
            }
        }
    }

    /**
     * Looks up the Minecraft map palette byte for an RGB triplet (0..255).
     */
    fun lookup(r: Int, g: Int, b: Int): Byte {
        val r5 = (r and 0xFF) ushr 3
        val g5 = (g and 0xFF) ushr 3
        val b5 = (b and 0xFF) ushr 3
        val key = (r5 shl 10) or (g5 shl 5) or b5
        return lookupTable[key]
    }

    /**
     * Fills the given 128x128 byte buffer with a solid map color (default: black).
     */
    fun fillBlank(buffer: ByteArray, color: Byte = BLACK_COLOR_BYTE) {
        Arrays.fill(buffer, color)
    }
}

