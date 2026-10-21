package su.plo.voice.discs.audio

import su.plo.voice.discs.visualizer.SpectrumAnalyzer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Analyze only frames being transmitted, never the decoder's look-ahead buffer. */
internal class PlaybackSpectrumTap(private val format: AudioDataFormat) : AutoCloseable {
    private val decoder = format.createDecoder()
    private val pcm = ByteBuffer.allocateDirect(format.totalSampleCount() * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer()
    private val channels = Array(format.channelCount) { FloatArray(format.chunkSampleCount) }

    fun accept(data: ByteArray, spectrum: SpectrumAnalyzer?, mix: MixAnalysis? = null, positionMs: Long = 0, scale: Double = 1.0) {
        pcm.clear()
        decoder.decode(data, pcm)
        val length = pcm.remaining() / channels.size
        for (sample in 0 until length) for (channel in channels.indices)
            channels[channel][sample] = pcm.get() / 32768f
        spectrum?.accept(channels, 0, length, format.sampleRate)
        mix?.accept(channels, 0, length, positionMs, scale)
    }

    override fun close() = decoder.close()
}

