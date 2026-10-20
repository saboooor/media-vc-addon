package su.plo.voice.discs.audio

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Splits a single 20 ms, 48 kHz interleaved stereo PCM frame before encoding either speaker. */
internal class StereoPcmFrameEncoder(
    private val leftEncoder: AudioChunkEncoder,
    private val rightEncoder: AudioChunkEncoder,
) {
    private val left = ByteBuffer.allocateDirect(960 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val right = ByteBuffer.allocateDirect(960 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    fun encode(pcm: ByteArray): Pair<ByteArray, ByteArray> {
        require(pcm.size == 960 * 2 * 2) { "Expected one 20 ms stereo PCM frame" }
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        left.clear()
        right.clear()
        repeat(960) {
            left.put(samples.get())
            right.put(samples.get())
        }
        left.flip()
        right.flip()
        return leftEncoder.encode(left) to rightEncoder.encode(right)
    }
}
