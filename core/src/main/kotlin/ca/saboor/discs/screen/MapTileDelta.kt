package ca.saboor.discs.screen

/** One rectangle in Minecraft's 128 by 128 map palette, packed row by row. */
internal data class MapTileRegion(val x: Int, val y: Int, val width: Int, val height: Int, val colors: ByteArray)

internal class MapTileDelta {
    private var previous: ByteArray? = null

    fun update(colors: ByteArray): MapTileRegion? {
        require(colors.size == 128 * 128)
        val old = previous
        if (old == null) {
            previous = colors.copyOf()
            return MapTileRegion(0, 0, 128, 128, colors.copyOf())
        }
        var minX = 128
        var minY = 128
        var maxX = -1
        var maxY = -1
        for (index in colors.indices) {
            if (colors[index] == old[index]) continue
            val x = index % 128
            val y = index / 128
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        if (maxX < 0) return null
        colors.copyInto(old)
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val packed = ByteArray(width * height)
        for (row in 0 until height) {
            val start = (minY + row) * 128 + minX
            colors.copyInto(packed, row * width, start, start + width)
        }
        return MapTileRegion(minX, minY, width, height, packed)
    }
}
