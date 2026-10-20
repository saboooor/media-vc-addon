package su.plo.voice.discs.screen

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Small independently decodable RGB tiles keep payloads under Bukkit's 32 KiB limit. */
internal class CompanionTileEncoder {
    private val pixels = IntArray(128 * 128)
    private var cached: ByteArray? = null
    fun encode(rgb: ByteArray, screenWidth: Int, tileX: Int, tileY: Int, quality: Int): ByteArray {
        var changed = cached == null
        for (y in 0 until 128) for (x in 0 until 128) {
            val offset = ((tileY + y) * screenWidth + tileX + x) * 3
            val color = ((rgb[offset].toInt() and 255) shl 16) or
                ((rgb[offset + 1].toInt() and 255) shl 8) or (rgb[offset + 2].toInt() and 255)
            val index = y * 128 + x
            if (pixels[index] != color) changed = true
            pixels[index] = color
        }
        if (!changed) return cached!!
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 128, 128, pixels, 0, 128)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = quality.coerceIn(40, 95) / 100f
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally { writer.dispose() }
        return bytes.toByteArray().also { require(it.size <= 32760); cached = it }
    }
}
