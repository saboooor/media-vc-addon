package ca.saboor.discs.screen

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Encodes a complete video display as one JPEG. Transport handles fragmentation. */
internal class CompanionFrameEncoder {
    private var pixels = IntArray(0)
    private var image: BufferedImage? = null
    private var cached: ByteArray? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedQuality = 0
    private var writer: ImageWriter? = null

    private fun getWriter(): ImageWriter {
        writer?.let { return it }
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        writer = w
        return w
    }

    fun encode(rgb: ByteArray, screenWidth: Int, screenHeight: Int, quality: Int): ByteArray {
        val totalPixels = screenWidth.toLong() * screenHeight
        require(screenWidth in 1..8192 && screenHeight in 1..8192 && totalPixels <= 8_294_400L)
        val pixelCount = screenWidth * screenHeight
        require(rgb.size == pixelCount * 3)

        if (image?.width != screenWidth || image?.height != screenHeight) {
            image = BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_RGB)
            pixels = (image!!.raster.dataBuffer as DataBufferInt).data
            cached = null
        }
        val jpegQuality = quality.coerceIn(40, 95)
        var changed = cached == null || cachedWidth != screenWidth || cachedHeight != screenHeight || cachedQuality != jpegQuality

        var offset = 0
        for (index in 0 until pixelCount) {
            val color = ((rgb[offset].toInt() and 255) shl 16) or
                ((rgb[offset + 1].toInt() and 255) shl 8) or (rgb[offset + 2].toInt() and 255)
            offset += 3
            if (pixels[index] != color) changed = true
            pixels[index] = color
        }
        if (!changed) return cached!!
        cached = null // Failed encodes must not reuse a JPEG from the previous pixel buffer.

        val imageWriter = getWriter()
        val estimatedSize = minOf(4 * 1024 * 1024, maxOf(32 * 1024, (pixelCount / 4)))
        val bytes = ByteArrayOutputStream(estimatedSize)
        MemoryCacheImageOutputStream(bytes).use { output ->
            imageWriter.output = output
            try {
                val parameters = imageWriter.defaultWriteParam
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = jpegQuality / 100f
                imageWriter.write(null, IIOImage(image, null, null), parameters)
            } finally {
                imageWriter.output = null
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= 4 * 1024 * 1024)
            cached = it; cachedWidth = screenWidth; cachedHeight = screenHeight; cachedQuality = jpegQuality
        }
    }
}
