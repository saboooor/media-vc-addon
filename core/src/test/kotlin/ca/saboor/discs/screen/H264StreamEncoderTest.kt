package ca.saboor.discs.screen

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class H264StreamEncoderTest {
    @Test fun `AUD parser survives fragmented start codes and finds IDR units`() {
        val result = mutableListOf<Pair<ByteArray, Boolean>>()
        val parser = H264AccessUnits { data, key -> result += data to key }
        val first = byteArrayOf(0, 0, 0, 1, 9, -16, 0, 0, 1, 0x65, 5, 6)
        val second = byteArrayOf(0, 0, 0, 1, 9, -16, 0, 0, 1, 0x41, 7)
        (first + second).forEach { parser.accept(it.toInt()) }
        parser.finish()
        assertEquals(2, result.size)
        assertArrayEquals(first, result[0].first); assertTrue(result[0].second)
        assertArrayEquals(second, result[1].first); assertFalse(result[1].second)
    }
    @Test fun `native encoder emits bounded dependent frames and stops accepting input on close`() {
        val available = runCatching { ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start().let {
            val text = it.inputStream.bufferedReader().readText(); it.waitFor() == 0 && text.contains("libx264")
        } }.getOrDefault(false)
        assumeTrue(available, "FFmpeg libx264 required")
        val frames = Collections.synchronizedList(mutableListOf<Pair<ByteArray, Boolean>>())
        val enough = CountDownLatch(8)
        val encoder = H264StreamEncoder("ffmpeg", 128, 72, 10, 1000) { _, _, data, key -> frames += data to key; enough.countDown() }
        try {
            repeat(20) { n ->
                val raw = ByteArray(128 * 72 * 3) { 40 }
                for (y in 10..30) for (x in n..n + 20) raw[(y * 128 + x) * 3] = 200.toByte()
                assertTrue(encoder.submit(raw)); Thread.sleep(40)
            }
            assertTrue(enough.await(3, TimeUnit.SECONDS))
            assertFalse(encoder.failed)
            assertTrue(frames.first().second)
            assertTrue(frames.any { !it.second })
            assertTrue(frames.all { it.first.size <= 4 * 1024 * 1024 })
            val keySize = frames.first().first.size
            assertTrue(frames.filterNot { it.second }.map { it.first.size }.average() < keySize)
        } finally { encoder.close() }
        assertFalse(encoder.submit(ByteArray(128 * 72 * 3)))
    }
}
