package su.plo.voice.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

class StereoPcmFrameEncoderTest {
    private class RecordingEncoder : AudioChunkEncoder {
        val frames = mutableListOf<ShortArray>()
        override fun encode(buffer: ShortBuffer): ByteArray {
            assertTrue(buffer.isDirect, "Native Opus requires direct PCM buffers")
            val samples = ShortArray(buffer.remaining())
            buffer.get(samples)
            frames += samples
            return byteArrayOf(frames.size.toByte())
        }
        override fun encode(buffer: ShortBuffer, out: ByteBuffer) { out.put(encode(buffer)) }
        override fun close() {}
    }

    private fun pcm(left: ShortArray, right: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(left.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        left.indices.forEach { buffer.putShort(left[it]); buffer.putShort(right[it]) }
        return buffer.array()
    }

    @Test fun `both encoders receive matching sample intervals from every shared frame`() {
        val left = RecordingEncoder()
        val right = RecordingEncoder()
        val encoder = StereoPcmFrameEncoder(left, right)
        repeat(4) { frame ->
            val leftSamples = ShortArray(960) { (frame * 960 + it).toShort() }
            val rightSamples = ShortArray(960) { (-frame * 960 - it - 1).toShort() }
            val encoded = encoder.encode(pcm(leftSamples, rightSamples))
            assertArrayEquals(leftSamples, left.frames[frame])
            assertArrayEquals(rightSamples, right.frames[frame])
            assertArrayEquals(byteArrayOf((frame + 1).toByte()), encoded.first)
            assertArrayEquals(encoded.first, encoded.second)
        }
    }

    @Test fun `duplicated mono samples remain identical at both speakers`() {
        val left = RecordingEncoder()
        val right = RecordingEncoder()
        val samples = ShortArray(960) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        StereoPcmFrameEncoder(left, right).encode(pcm(samples, samples))
        assertArrayEquals(samples, left.frames.single())
        assertArrayEquals(samples, right.frames.single())
    }

    @Test fun `partial frames cannot advance either encoder`() {
        val left = RecordingEncoder()
        val right = RecordingEncoder()
        val encoder = StereoPcmFrameEncoder(left, right)
        for (size in listOf(0, 1920, 3839, 3841)) {
            assertThrows(IllegalArgumentException::class.java) { encoder.encode(ByteArray(size)) }
        }
        assertTrue(left.frames.isEmpty())
        assertTrue(right.frames.isEmpty())
    }

    @Test fun `native mono opus packets decode to one complete frame for both speakers`() {
        val format = OpusAudioDataFormat(1, 48_000, 960)
        val left = format.createEncoder(AudioConfiguration())
        val right = format.createEncoder(AudioConfiguration())
        val decoder = format.createDecoder()
        try {
            val encoder = StereoPcmFrameEncoder(left, right)
            val samples = ShortArray(960) { (kotlin.math.sin(it * 0.1) * 12_000).toInt().toShort() }
            val pair = encoder.encode(pcm(samples, samples))
            val output = ByteBuffer.allocateDirect(960 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (packet in listOf(pair.first, pair.second)) {
                assertTrue(packet.isNotEmpty())
                decoder.decode(packet, output)
                assertEquals(960, output.remaining())
            }
        } finally {
            decoder.close()
            right.close()
            left.close()
        }
    }
}
