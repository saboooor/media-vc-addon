package ca.saboor.discs.screen

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Encodes a complete video display as one JPEG. Transport handles fragmentation. */
internal class CompanionFrameEncoder {
    private var pixels = IntArray(0)
    private var image: BufferedImage? = null
    private var cached: ByteArray? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedQuality = 0
    fun encode(rgb: ByteArray, screenWidth: Int, screenHeight: Int, quality: Int): ByteArray {
        require(screenWidth in 1..8192 && screenHeight in 1..8192 && screenWidth.toLong() * screenHeight <= 8_294_400L)
        if (image?.width != screenWidth || image?.height != screenHeight) {
            image = BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_RGB)
            pixels = (image!!.raster.dataBuffer as DataBufferInt).data
            cached = null
        }
        val jpegQuality = quality.coerceIn(40, 95)
        var changed = cached == null || cachedWidth != screenWidth || cachedHeight != screenHeight || cachedQuality != jpegQuality
        for (y in 0 until screenHeight) for (x in 0 until screenWidth) {
            val offset = (y * screenWidth + x) * 3
            val color = ((rgb[offset].toInt() and 255) shl 16) or
                ((rgb[offset + 1].toInt() and 255) shl 8) or (rgb[offset + 2].toInt() and 255)
            val index = y * screenWidth + x
            if (pixels[index] != color) changed = true
            pixels[index] = color
        }
        if (!changed) return cached!!
        cached = null // Failed encodes must not reuse a JPEG from the previous pixel buffer.
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = jpegQuality / 100f
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally { writer.dispose() }
        return bytes.toByteArray().also {
            require(it.size <= 4 * 1024 * 1024)
            cached = it; cachedWidth = screenWidth; cachedHeight = screenHeight; cachedQuality = jpegQuality
        }
    }
}
