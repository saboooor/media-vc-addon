package su.plo.voice.discs.audio

import su.plo.voice.discs.audio.sonic.Sonic
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import kotlin.math.pow

/** tempo preserves pitch; pitch preserves duration; rate changes both (legacy Nightcore). */
data class TimescaleParameters(val tempo: Double = 1.0, val pitchSemitones: Double = 0.0, val rate: Double = 1.0) {
    init {
        require(tempo.isFinite() && tempo in 0.4..2.2)
        require(pitchSemitones.isFinite() && pitchSemitones in -12.0..12.0)
        require(rate.isFinite() && rate in 0.25..4.0)
    }
    val pitch get() = 2.0.pow(pitchSemitones / 12.0)
    val playbackRate get() = tempo * rate
    val identity get() = tempo == 1.0 && pitchSemitones == 0.0 && rate == 1.0
}

/** One interleaved stereo processor preserves channel alignment; no native libraries required. */
class TimescaleFilter(
    private val downstream: FloatPcmAudioFilter,
    private val channels: Int,
    private val sampleRate: Int,
    private val parameters: () -> TimescaleParameters,
    private val onOutput: ((Int, Double) -> Unit)? = null,
) : FloatPcmAudioFilter {
    private var sonic: Sonic? = null
    private var previous: TimescaleParameters? = null
    private var input = FloatArray(4096 * channels)
    private val interleaved = FloatArray(4096 * channels)
    private val output = Array(channels) { FloatArray(4096) }
    private var closed = false

    override fun process(samples: Array<FloatArray>, offset: Int, length: Int) {
        check(!closed)
        require(samples.size == channels && offset >= 0 && length >= 0 && samples.all { offset + length <= it.size })
        val settings = parameters()
        if (settings.identity && sonic == null) {
            onOutput?.invoke(length, 1.0)
            downstream.process(samples, offset, length)
            return
        }
        val processor = sonic ?: Sonic(sampleRate, channels).also { it.quality = 1; sonic = it }
        if (settings != previous) {
            processor.speed = settings.tempo.toFloat()
            processor.pitch = settings.pitch.toFloat()
            // Sonic resets its resampler phase on setRate; avoid calling it for tempo-only updates.
            if (previous?.rate != settings.rate) processor.rate = settings.rate.toFloat()
            previous = settings
        }
        if (input.size < length * channels) input = FloatArray(length * channels)
        for (i in 0 until length) for (channel in 0 until channels) input[i * channels + channel] = samples[channel][offset + i]
        processor.writeFloatToStream(input, length)
        drain(settings.playbackRate)
    }
    private fun drain(rate: Double) {
        val processor = sonic ?: return
        while (processor.samplesAvailable() > 0) {
            val count = processor.readFloatFromStream(interleaved, 4096)
            if (count <= 0) break
            for (i in 0 until count) for (channel in 0 until channels) output[channel][i] = interleaved[i * channels + channel]
            onOutput?.invoke(count, rate)
            downstream.process(output, 0, count)
        }
    }
    override fun seekPerformed(requested: Long, provided: Long) {
        // Discard old DSP buffers; a seek must never flush pre-seek audio into the new stream.
        sonic = null; previous = null
        downstream.seekPerformed(requested, provided)
    }
    override fun flush() {
        if (closed) return
        sonic?.flushStream()
        drain(previous?.playbackRate ?: 1.0)
        downstream.flush()
    }
    override fun close() { closed = true; sonic = null; previous = null; downstream.close() }
}

/** Converts transmitted output samples back to source-media time, including decoder look-ahead. */
class TimescaleClock(private val sampleRate: Int = 48_000) {
    private data class Segment(var samples: Int, val rate: Double)
    private val segments = ArrayDeque<Segment>()
    private var position: Double? = null
    private var mapped = false
    @Synchronized fun decoded(samples: Int, rate: Double) {
        require(samples >= 0 && rate.isFinite() && rate > 0)
        if (samples == 0) return
        mapped = true
        if (segments.lastOrNull()?.rate == rate) segments.last().samples += samples
        else segments.addLast(Segment(samples, rate))
    }
    @Synchronized fun sent(rawTimecode: Long, samples: Int = 960): Long {
        if (!mapped) return rawTimecode
        if (position == null) position = rawTimecode.toDouble()
        val start = position!!.toLong()
        var remaining = samples
        while (remaining > 0) {
            val segment = segments.firstOrNull()
            val count = minOf(remaining, segment?.samples ?: remaining)
            position = position!! + count * 1000.0 / sampleRate * (segment?.rate ?: 1.0)
            if (segment != null) { segment.samples -= count; if (segment.samples == 0) segments.removeFirst() }
            remaining -= count
        }
        return start
    }
    @Synchronized fun reset(positionMs: Long? = null) { segments.clear(); position = positionMs?.toDouble(); mapped = false }
}
