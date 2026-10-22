package ca.saboor.discs.screen

import kotlin.math.floor
import kotlin.math.min

internal object CompanionResolution {
    data class Size(val width: Int, val height: Int)

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
        val match = Regex("\\bs:(\\d+)x(\\d+)\\b").find(line) ?: return null
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
        if (output.size != targetWidth * targetHeight * 3) output = ByteArray(targetWidth * targetHeight * 3)
        for (y in 0 until targetHeight) {
            val sy = ((y + .5) * height / targetHeight - .5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sy.toInt(); val y1 = (y0 + 1).coerceAtMost(height - 1); val fy = sy - y0
            for (x in 0 until targetWidth) {
                val sx = ((x + .5) * width / targetWidth - .5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = sx.toInt(); val x1 = (x0 + 1).coerceAtMost(width - 1); val fx = sx - x0
                for (c in 0..2) {
                    fun sample(px: Int, py: Int) = source[(py * width + px) * 3 + c].toInt() and 255
                    val top = sample(x0, y0) * (1 - fx) + sample(x1, y0) * fx
                    val bottom = sample(x0, y1) * (1 - fx) + sample(x1, y1) * fx
                    output[(y * targetWidth + x) * 3 + c] = (top * (1 - fy) + bottom * fy + .5).toInt().toByte()
                }
            }
        }
        return output
    }
}
