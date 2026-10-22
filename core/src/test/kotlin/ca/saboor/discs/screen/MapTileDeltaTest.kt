package ca.saboor.discs.screen

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MapTileDeltaTest {
    @Test fun `first frame is complete and identical frames send nothing`() {
        val delta = MapTileDelta()
        val frame = ByteArray(16384) { (it % 251).toByte() }
        val region = delta.update(frame)!!
        assertEquals(listOf(0, 0, 128, 128), listOf(region.x, region.y, region.width, region.height))
        assertArrayEquals(frame, region.colors)
        assertNull(delta.update(frame))
        frame[16383] = 99
        assertNotEquals(frame[16383], region.colors[16383], "Packet colors must survive buffer reuse")
        val corner = delta.update(frame)!!
        assertEquals(listOf(127, 127, 1, 1), listOf(corner.x, corner.y, corner.width, corner.height))
        assertArrayEquals(byteArrayOf(99), corner.colors)
    }

    @Test fun `packed region covers changes on multiple rows without including outer columns`() {
        val delta = MapTileDelta()
        val frame = ByteArray(16384)
        delta.update(frame)
        frame[5 * 128 + 3] = 7
        frame[7 * 128 + 6] = 8
        val region = delta.update(frame)!!
        assertEquals(listOf(3, 5, 4, 3), listOf(region.x, region.y, region.width, region.height))
        assertArrayEquals(byteArrayOf(7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8), region.colors)
        assertNull(delta.update(frame))
    }

    @Test fun `client reconstructs exact frames through sparse changes full changes and unchanged frames`() {
        val delta = MapTileDelta()
        val source = ByteArray(16384)
        val client = ByteArray(16384)
        val random = Random(42)
        repeat(60) { step ->
            when (step % 3) {
                0 -> repeat(4) { source[random.nextInt(source.size)] = random.nextInt(256).toByte() }
                1 -> random.nextBytes(source)
            }
            delta.update(source)?.let { region ->
                for (row in 0 until region.height) {
                    region.colors.copyInto(client, (region.y + row) * 128 + region.x,
                        row * region.width, (row + 1) * region.width)
                }
            }
            assertArrayEquals(source, client, "Frame $step")
        }
    }
}
