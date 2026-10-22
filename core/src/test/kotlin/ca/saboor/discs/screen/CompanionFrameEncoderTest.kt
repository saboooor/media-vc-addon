package ca.saboor.discs.screen

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanionFrameEncoderTest {
    @Test fun `one JPEG contains the entire multi-frame display`() {
        val source = ByteArray(256 * 256 * 3)
        for (y in 0 until 256) for (x in 0 until 256) {
            val i = (y * 256 + x) * 3
            source[i] = if (x < 128) 201.toByte() else 19
            source[i + 1] = if (y < 128) 73 else 170.toByte()
            source[i + 2] = 121
        }
        val encoder = CompanionFrameEncoder()
        val encoded = encoder.encode(source, 256, 256, 85)
        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        assertEquals(256, decoded.width)
        assertEquals(256, decoded.height)
        for (y in listOf(64, 192)) for (x in listOf(64, 192)) {
            val color = decoded.getRGB(x, y)
            assertTrue(kotlin.math.abs(((color shr 16) and 255) - if (x < 128) 201 else 19) <= 3)
            assertTrue(kotlin.math.abs(((color shr 8) and 255) - if (y < 128) 73 else 170) <= 3)
        }
        assertSame(encoded, encoder.encode(source, 256, 256, 85))
        source[(64 * 256 + 192) * 3] = 255.toByte()
        assertNotSame(encoded, encoder.encode(source, 256, 256, 85))
    }

    @Test fun `HD JPEG dimensions are independent of map tile layout`() {
        val source = ByteArray(1920 * 1080 * 3) { 80 }
        val encoded = CompanionFrameEncoder().encode(source, 1920, 1080, 85)
        val image = ImageIO.read(ByteArrayInputStream(encoded))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
    }

    @Test fun `complete JPEG may exceed one plugin message`() {
        val source = Random(23).nextBytes(512 * 256 * 3)
        val encoded = CompanionFrameEncoder().encode(source, 512, 256, 95)
        assertTrue(encoded.size > 32764)
        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        assertEquals(512, decoded.width)
        assertEquals(256, decoded.height)
    }

    @Test fun `cache invalidates when JPEG quality or dimensions change`() {
        val source = Random(9).nextBytes(256 * 128 * 3)
        val encoder = CompanionFrameEncoder()
        val original = encoder.encode(source, 256, 128, 85)
        val changedQuality = encoder.encode(source, 256, 128, 40)
        assertNotSame(original, changedQuality)
        assertSame(changedQuality, encoder.encode(source, 256, 128, 40))
        val changedShape = encoder.encode(source, 128, 256, 40)
        assertNotSame(changedQuality, changedShape)
        val image = ImageIO.read(ByteArrayInputStream(changedShape))
        assertEquals(128, image.width)
        assertEquals(256, image.height)
    }
}
