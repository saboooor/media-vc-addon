package ca.saboor.discs.screen

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoDecodeBufferTest {
    @Test fun `decoder runs ahead while presentation waits but stops at the memory limit`() = runBlocking {
        val buffer = VideoDecodeBuffer(6, 1, 24)
        assertEquals(3, buffer.capacity)
        var decoded = 0
        val producer = launch {
            buffer.decode { pixels ->
                if (decoded == 5) null else {
                    pixels.fill(decoded.toByte())
                    (decoded++ * 40).toLong()
                }
            }
        }
        try {
            yield()
            assertEquals(3, decoded, "Decode continues without waiting for audio/presentation")
            assertTrue(buffer.waitingForBuffer, "Full decode buffer reports intentional backpressure")
            val first = buffer.frames.receive()
            assertEquals(0L, first.timestampMs)
            yield()
            assertEquals(3, decoded, "The presenting frame counts towards the memory ceiling")
            assertTrue(first.pixels.all { it == 0.toByte() })
            buffer.release(first)
            yield()
            assertEquals(4, decoded)
            for (expected in 1..4) {
                val frame = buffer.frames.receive()
                assertEquals(expected * 40L, frame.timestampMs)
                assertTrue(frame.pixels.all { it == expected.toByte() }, "Queued buffers cannot be overwritten")
                buffer.release(frame)
            }
            producer.join()
            assertTrue(buffer.frames.receiveCatching().isClosed)
        } finally { producer.cancelAndJoin() }
    }

    @Test fun `decoder failure reaches presentation after queued frames`() = runBlocking {
        val buffer = VideoDecodeBuffer(6, 15)
        val producer = launch {
            var reads = 0
            buffer.decode { if (reads++ == 0) 42L else throw IOException("Decoder failed") }
        }
        val frame = buffer.frames.receive()
        assertEquals(42L, frame.timestampMs)
        buffer.release(frame)
        assertEquals("Decoder failed", buffer.frames.receiveCatching().exceptionOrNull()?.message)
        producer.join()
    }

    @Test fun `paused or abandoned presentation can cancel a full buffer`() = runBlocking {
        val buffer = VideoDecodeBuffer(6, 30, 12)
        assertEquals(2, buffer.capacity)
        var decoded = 0
        val producer = launch { buffer.decode { decoded++; 0L } }
        yield()
        assertEquals(2, decoded)
        producer.cancelAndJoin()
        assertTrue(producer.isCancelled)
    }

    @Test fun `four K buffering respects the per-screen memory ceiling`() {
        val bytes = 3840 * 2160 * 6
        val buffer = VideoDecodeBuffer(bytes, 30)
        assertEquals(2, buffer.capacity)
        assertTrue(buffer.capacity.toLong() * bytes <= 96L * 1024 * 1024)
    }
    @Test fun `presentation wait does not hide stalled decode and full pool does not start a read`() = runBlocking {
        val buffer = VideoDecodeBuffer(6, 1, 12)
        val monitor = VideoReadMonitor(15)
        val allowRead = CompletableDeferred<Unit>()
        val reading = CompletableDeferred<Unit>()
        var reads = 0
        val producer = launch {
            buffer.decode {
                monitor.beginRead(0)
                try {
                    if (++reads == 2) {
                        reading.complete(Unit)
                        allowRead.await()
                    }
                    0L
                } finally { monitor.endRead() }
            }
        }
        try {
            val presenting = buffer.frames.receive()
            reading.await()
            assertTrue(monitor.isStalled(15, false), "Waiting to present a frame must not mask a blocked read")
            allowRead.complete(Unit)
            yield()
            assertTrue(buffer.waitingForBuffer)
            assertFalse(monitor.isStalled(100, false), "A full frame pool has no read in progress")
            buffer.release(presenting)
        } finally { producer.cancelAndJoin() }
    }
}
