package ca.saboor.discs.screen

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Encodes a complete video display as one JPEG. Transport handles fragmentation. */
internal class CompanionFrameEncoder {
    private var pixels = IntArray(0)
    private var cached: ByteArray? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedQuality = 0
    fun encode(rgb: ByteArray, screenWidth: Int, screenHeight: Int, quality: Int): ByteArray {
        require(screenWidth > 0 && screenHeight > 0 && screenWidth.toLong() * screenHeight <= 256L * 128 * 128)
        if (pixels.size != screenWidth * screenHeight) { pixels = IntArray(screenWidth * screenHeight); cached = null }
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
        val image = BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, screenWidth, screenHeight, pixels, 0, screenWidth)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { output ->
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
