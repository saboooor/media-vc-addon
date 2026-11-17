package ca.saboor.discs.screen

import kotlin.math.floor
import kotlin.math.min

internal object CompanionResolution {
    data class Size(val width: Int, val height: Int)
    data class MapSizeLimit(val columns: Int, val rows: Int)

    private val SHOWINFO_SIZE_REGEX = Regex("\\bs:(\\d+)x(\\d+)\\b")
    private val MAP_SIZE_LIMIT_REGEX = Regex("^(\\d+)x(\\d+)$", RegexOption.IGNORE_CASE)

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

    fun cappedSize(mapWidth: Int, mapHeight: Int, requested: String, maximum: String): Size {
        val requestedSize = size(mapWidth, mapHeight, requested)
        val maximumSize = size(mapWidth, mapHeight, maximum)
        val factor = min(
            1.0,
            min(
                maximumSize.width.toDouble() / requestedSize.width,
                maximumSize.height.toDouble() / requestedSize.height
            )
        )
        return Size(
            floor(requestedSize.width * factor).toInt().coerceAtLeast(1),
            floor(requestedSize.height * factor).toInt().coerceAtLeast(1)
        )
    }

    fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Size {
        val factor = min(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        return Size(
            floor(width * factor).toInt().coerceAtLeast(1),
            floor(height * factor).toInt().coerceAtLeast(1)
        )
    }

    fun parseMapSizeLimit(setting: String): MapSizeLimit? {
        val match = MAP_SIZE_LIMIT_REGEX.matchEntire(setting.trim()) ?: return null
        val columns = match.groupValues[1].toIntOrNull() ?: return null
        val rows = match.groupValues[2].toIntOrNull() ?: return null
        return if (columns > 0 && rows > 0) MapSizeLimit(columns, rows) else null
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
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedTargetWidth = 0
    private var cachedTargetHeight = 0
    private var x0s = IntArray(0)
    private var x1s = IntArray(0)
    private var fxs = IntArray(0)
    private var y0s = IntArray(0)
    private var y1s = IntArray(0)
    private var fys = IntArray(0)

    fun scale(source: ByteArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): ByteArray {
        require(source.size == width * height * 3)
        if (width == targetWidth && height == targetHeight) return source
        val targetSize = targetWidth * targetHeight * 3
        if (output.size != targetSize) output = ByteArray(targetSize)

        if (width != cachedWidth || height != cachedHeight || targetWidth != cachedTargetWidth || targetHeight != cachedTargetHeight) {
            cachedWidth = width
            cachedHeight = height
            cachedTargetWidth = targetWidth
            cachedTargetHeight = targetHeight
            x0s = IntArray(targetWidth)
            x1s = IntArray(targetWidth)
            fxs = IntArray(targetWidth)
            for (x in 0 until targetWidth) {
                val sx = ((x + 0.5) * width / targetWidth - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = sx.toInt()
                x0s[x] = x0 * 3
                x1s[x] = (x0 + 1).coerceAtMost(width - 1) * 3
                fxs[x] = ((sx - x0) * 1024.0 + 0.5).toInt()
            }
            y0s = IntArray(targetHeight)
            y1s = IntArray(targetHeight)
            fys = IntArray(targetHeight)
            for (y in 0 until targetHeight) {
                val sy = ((y + 0.5) * height / targetHeight - 0.5).coerceIn(0.0, (height - 1).toDouble())
                val y0 = sy.toInt()
                y0s[y] = y0 * width * 3
                y1s[y] = (y0 + 1).coerceAtMost(height - 1) * width * 3
                fys[y] = ((sy - y0) * 1024.0 + 0.5).toInt()
            }
        }

        val lx0 = x0s
        val lx1 = x1s
        val lfx = fxs
        val out = output
        var outOffset = 0
        for (y in 0 until targetHeight) {
            val fy = fys[y]
            val oneMinusFy = 1024 - fy
            val row0 = y0s[y]
            val row1 = y1s[y]

            for (x in 0 until targetWidth) {
                val fx = lfx[x]
                val oneMinusFx = 1024 - fx

                val p00 = row0 + lx0[x]
                val p10 = row0 + lx1[x]
                val p01 = row1 + lx0[x]
                val p11 = row1 + lx1[x]

                for (c in 0..2) {
                    val top = (source[p00 + c].toInt() and 255) * oneMinusFx + (source[p10 + c].toInt() and 255) * fx
                    val bottom = (source[p01 + c].toInt() and 255) * oneMinusFx + (source[p11 + c].toInt() and 255) * fx
                    out[outOffset++] = ((top * oneMinusFy + bottom * fy + 524288) shr 20).toByte()
                }
            }
        }
        return out
    }
}
