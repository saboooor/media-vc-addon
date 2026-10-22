package ca.saboor.discs.audio

import ca.saboor.discs.visualizer.SpectrumAnalyzer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.AudioDataFormat

/** Analyze only frames being transmitted, never the decoder's look-ahead buffer. */
internal class PlaybackSpectrumTap(private val format: AudioDataFormat) : AutoCloseable {
    private val decoder = format.createDecoder()
    private val pcm = ByteBuffer.allocateDirect(format.totalSampleCount() * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer()
    private val channels = Array(format.channelCount) { FloatArray(format.chunkSampleCount) }

    fun accept(data: ByteArray, spectrum: SpectrumAnalyzer?, mix: MixAnalysis? = null, positionMs: Long = 0, scale: Double = 1.0): Boolean {
        pcm.clear()
        decoder.decode(data, pcm)
        val length = pcm.remaining() / channels.size
        var silent = length > 0
        for (sample in 0 until length) for (channel in channels.indices) {
            val value = pcm.get().toInt()
            if (value != 0) silent = false
            channels[channel][sample] = value / 32768f
        }
        spectrum?.accept(channels, 0, length, format.sampleRate)
        mix?.accept(channels, 0, length, positionMs, scale)
        return silent
    }

    override fun close() = decoder.close()
}

