package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class CompanionTileEncoderTest {
    @Test fun `RGB tiles retain color and use the correct screen offsets`() {
        val source = ByteArray(256 * 128 * 3)
        for (y in 0 until 128) for (x in 0 until 256) {
            val i = (y * 256 + x) * 3
            source[i] = if (x < 128) 201.toByte() else 19
            source[i + 1] = 73
            source[i + 2] = 121
        }
        val encoder = CompanionTileEncoder()
        val encoded = encoder.encode(source, 256, 128, 0, 85)
        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        assertEquals(128, decoded.width)
        assertEquals(128, decoded.height)
        val color = decoded.getRGB(64, 64)
        assertTrue(kotlin.math.abs(((color shr 16) and 255) - 19) <= 3)
        assertTrue(kotlin.math.abs(((color shr 8) and 255) - 73) <= 3)
        assertTrue(kotlin.math.abs((color and 255) - 121) <= 3)
        assertSame(encoded, encoder.encode(source, 256, 128, 0, 85))
        source[(64 * 256 + 192) * 3] = 255.toByte()
        assertNotSame(encoded, encoder.encode(source, 256, 128, 0, 85))
    }
    @Test fun `worst case noise fits a plugin message at maximum JPEG quality`() {
        val source = Random(23).nextBytes(128 * 128 * 3)
        val encoded = CompanionTileEncoder().encode(source, 128, 0, 0, 95)
        assertTrue(encoded.size + 4 <= 32764)
        assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)))
    }
}
