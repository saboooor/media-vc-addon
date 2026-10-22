package ca.saboor.discs.screen

import kotlin.math.floor
import kotlin.math.min

internal object CompanionResolution {
    data class Size(val width: Int, val height: Int)

    private val SHOWINFO_SIZE_REGEX = Regex("\\bs:(\\d+)x(\\d+)\\b")

    fun size(mapWidth: Int, mapHeight: Int, setting: String): Size {
        val box = when (setting.lowercase()) {
            "720p" -> Size(1280, 720)
            "1080p" -> Size(1920, 1080)
            "source" -> Size(3840, 2160)
            else -> return Size(mapWidth, mapHeight)
        }
        val factor = min(box.width.toDouble() / mapWidth, box.height.toDouble() / mapHeight)
        return Size(floor(mapWidth * factor).toInt().coerceAtLeast(1),
            floor(mapHeight * factor).toInt().coerceAtLeast(1))
    }

    fun parseOutputSize(line: String): Size? {
        if (!line.contains("showinfo")) return null
        val match = SHOWINFO_SIZE_REGEX.find(line) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 8_294_400)
        return Size(width, height)
    }
}

/** Reuses a map-sized RGB buffer; the companion keeps the original high-resolution buffer. */
internal class RgbFrameScaler {
    private var output = ByteArray(0)
    fun scale(source: ByteArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): ByteArray {
        require(source.size == width * height * 3)
        if (width == targetWidth && height == targetHeight) return source
        val targetSize = targetWidth * targetHeight * 3
        if (output.size != targetSize) output = ByteArray(targetSize)

        val x0s = IntArray(targetWidth)
        val x1s = IntArray(targetWidth)
        val fxs = DoubleArray(targetWidth)
        for (x in 0 until targetWidth) {
            val sx = ((x + 0.5) * width / targetWidth - 0.5).coerceIn(0.0, (width - 1).toDouble())
            val x0 = sx.toInt()
            x0s[x] = x0
            x1s[x] = (x0 + 1).coerceAtMost(width - 1)
            fxs[x] = sx - x0
        }

        var outOffset = 0
        for (y in 0 until targetHeight) {
            val sy = ((y + 0.5) * height / targetHeight - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = sy - y0
            val oneMinusFy = 1.0 - fy

            val row0 = y0 * width
            val row1 = y1 * width

            for (x in 0 until targetWidth) {
                val x0 = x0s[x]
                val x1 = x1s[x]
                val fx = fxs[x]
                val oneMinusFx = 1.0 - fx

                val p00 = (row0 + x0) * 3
                val p10 = (row0 + x1) * 3
                val p01 = (row1 + x0) * 3
                val p11 = (row1 + x1) * 3

                for (c in 0..2) {
                    val top = (source[p00 + c].toInt() and 255) * oneMinusFx + (source[p10 + c].toInt() and 255) * fx
                    val bottom = (source[p01 + c].toInt() and 255) * oneMinusFx + (source[p11 + c].toInt() and 255) * fx
                    output[outOffset++] = (top * oneMinusFy + bottom * fy + 0.5).toInt().toByte()
                }
            }
        }
        return output
    }
}
