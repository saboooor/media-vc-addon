package su.plo.voice.discs.audio

import su.plo.voice.discs.SpeakerChannels
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer
import java.util.Arrays

enum class EqPreset(val id: String, val label: String, val bands: FloatArray) {
    FLAT("flat", "Flat / Off", FloatArray(15) { 0f }),
    BASS("bass", "Bass Boost", floatArrayOf(0.6f, 0.7f, 0.8f, 0.55f, 0.25f, 0f, -0.25f, -0.25f, -0.25f, -0.25f, -0.25f, -0.25f, 0f, 0f, 0f)),
    BOOST("boost", "Punchy Boost", floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    MAXED("maxed", "Maxed", FloatArray(15) { 1.0f }),
    PARTY("party", "Club / Party", floatArrayOf(-0.25f, 0.28f, 0.42f, 0.5f, 0.36f, 0f, -0.25f, -0.21f, -0.21f, 0f, 0f, 0f, 0f, 0f, 0f)),
    POP("pop", "Vocal / Pop", floatArrayOf(-0.25f, 0.48f, 0.59f, 0.72f, 0.56f, 0.15f, -0.24f, -0.24f, -0.16f, -0.16f, 0f, 0f, 0f, 0f, 0f)),
    RADIO("radio", "Old Radio", floatArrayOf(0.65f, 0.45f, -0.25f, -0.25f, -0.25f, 0.45f, 0.55f, 0.6f, 0.6f, 0.6f, 0f, 0f, 0f, 0f, 0f)),
    SOFT("soft", "Soft / Mellow", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -0.25f, -0.25f, -0.25f, -0.25f, -0.25f, -0.25f, -0.25f)),
    TREBLE("treb", "Treble Boost", floatArrayOf(0.6f, 0.67f, 0.67f, 0f, -0.25f, 0.15f, -0.25f, 0.23f, 0.35f, 0.45f, 0.55f, 0.6f, 0.55f, 0f, 0f));

    companion object {
        fun fromId(id: String): EqPreset? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
    }
}

data class AudioFilterConfig(
    val eqPreset: String? = null,
    val bands: FloatArray? = null,
    val rotation: Boolean = false,
    val echo: Boolean = false,
    val tremolo: Boolean = false,
    val vibrato: Boolean = false,
    val underwater: Boolean = false,
    val speed: Double = 1.0,
    val tempo: Double = 1.0,
    val pitchSemitones: Double = 0.0,
) {
    fun hasActiveEq(): Boolean = (bands != null && bands.any { it != 0f }) ||
        (eqPreset != null && !eqPreset.equals("flat", ignoreCase = true) && !eqPreset.equals("off", ignoreCase = true))

    fun effectiveBands(): FloatArray? {
        if (bands != null) return bands
        val preset = eqPreset?.let { EqPreset.fromId(it) }
        return if (preset != null && preset != EqPreset.FLAT) preset.bands else null
    }

    fun hasActiveEffects(): Boolean = rotation || echo || tremolo || vibrato || underwater || speed != 1.0 || tempo != 1.0 || pitchSemitones != 0.0

    fun isEmpty(): Boolean = !hasActiveEq() && !hasActiveEffects()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFilterConfig) return false
        return eqPreset == other.eqPreset &&
            Arrays.equals(bands, other.bands) &&
            rotation == other.rotation &&
            echo == other.echo &&
            tremolo == other.tremolo &&
            vibrato == other.vibrato &&
            underwater == other.underwater &&
            speed == other.speed && tempo == other.tempo && pitchSemitones == other.pitchSemitones
    }

    override fun hashCode(): Int {
        var result = eqPreset?.hashCode() ?: 0
        result = 31 * result + (bands?.let { Arrays.hashCode(it) } ?: 0)
        result = 31 * result + rotation.hashCode()
        result = 31 * result + echo.hashCode()
        result = 31 * result + tremolo.hashCode()
        result = 31 * result + vibrato.hashCode()
        result = 31 * result + underwater.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + tempo.hashCode()
        result = 31 * result + pitchSemitones.hashCode()
        return result
    }
}

class EchoFilter(
    private val downstream: FloatPcmAudioFilter,
    sampleRate: Int = 48000,
    delaySeconds: Double = 0.35,
    private val decay: Float = 0.38f,
) : FloatPcmAudioFilter {
    private val delaySamples = (delaySeconds * sampleRate).toInt().coerceIn(1, 48000 * 2)
    private var ringBuffer: Array<FloatArray>? = null
    private var writePos = 0

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        val channels = input.size
        val buf = ringBuffer?.takeIf { it.size == channels } ?: Array(channels) { FloatArray(delaySamples) }.also { ringBuffer = it }
        for (i in 0 until length) {
            val idx = offset + i
            for (c in 0 until channels) {
                val inSample = input[c][idx]
                val delayed = buf[c][writePos]
                buf[c][writePos] = inSample + delayed * decay
                input[c][idx] = (inSample + delayed * decay).coerceIn(-1.0f, 1.0f)
            }
            writePos = (writePos + 1) % delaySamples
        }
        downstream.process(input, offset, length)
    }

    override fun seekPerformed(requested: Long, provided: Long) {
        ringBuffer?.forEach { it.fill(0f) }
        downstream.seekPerformed(requested, provided)
    }

    override fun flush() { downstream.flush() }
    override fun close() { downstream.close() }
}

class RotationFilter(
    private val downstream: FloatPcmAudioFilter,
    private val sampleRate: Int = 48000,
    private val rotationHz: Double = 0.2, // 1 complete rotation every 5 seconds
) : FloatPcmAudioFilter {
    private var sampleIndex = 0L

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (input.size < 2) {
            downstream.process(input, offset, length)
            return
        }
        val left = input[0]
        val right = input[1]
        val twoPi = 2.0 * Math.PI
        for (i in 0 until length) {
            val idx = offset + i
            val angle = twoPi * (sampleIndex % (sampleRate * 10)) * rotationHz / sampleRate
            val pan = Math.sin(angle) // -1.0 to 1.0
            val leftGain = Math.cos((pan + 1.0) * Math.PI / 4.0).toFloat()
            val rightGain = Math.sin((pan + 1.0) * Math.PI / 4.0).toFloat()
            left[idx] *= leftGain
            right[idx] *= rightGain
            sampleIndex++
        }
        downstream.process(input, offset, length)
    }

    override fun seekPerformed(requested: Long, provided: Long) {
        sampleIndex = 0L
        downstream.seekPerformed(requested, provided)
    }

    override fun flush() { downstream.flush() }
    override fun close() { downstream.close() }
}

class TremoloFilter(
    private val downstream: FloatPcmAudioFilter,
    private val sampleRate: Int = 48000,
    private val frequency: Double = 5.0, // Hz
    private val depth: Float = 0.6f, // 0..1
) : FloatPcmAudioFilter {
    private var sampleIndex = 0L

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        val twoPi = 2.0 * Math.PI
        for (i in 0 until length) {
            val idx = offset + i
            val angle = twoPi * (sampleIndex % (sampleRate * 10)) * frequency / sampleRate
            val mod = (1.0f - depth) + depth * (0.5f * (1.0f + Math.sin(angle).toFloat()))
            for (c in input.indices) {
                input[c][idx] *= mod
            }
            sampleIndex++
        }
        downstream.process(input, offset, length)
    }

    override fun seekPerformed(requested: Long, provided: Long) {
        sampleIndex = 0L
        downstream.seekPerformed(requested, provided)
    }

    override fun flush() { downstream.flush() }
    override fun close() { downstream.close() }
}

class VibratoFilter(
    private val downstream: FloatPcmAudioFilter,
    private val sampleRate: Int = 48000,
    private val frequency: Double = 6.0, // Hz
    private val depthSec: Double = 0.002, // 2ms modulation
) : FloatPcmAudioFilter {
    private val maxDelaySamples = (0.015 * sampleRate).toInt().coerceAtLeast(10) // 15ms buffer
    private var ringBuffer: Array<FloatArray>? = null
    private var writePos = 0
    private var sampleIndex = 0L

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        val channels = input.size
        val buf = ringBuffer?.takeIf { it.size == channels } ?: Array(channels) { FloatArray(maxDelaySamples) }.also { ringBuffer = it }
        val centerDelay = maxDelaySamples / 2.0
        val maxMod = depthSec * sampleRate
        val twoPi = 2.0 * Math.PI

        for (i in 0 until length) {
            val idx = offset + i
            val angle = twoPi * (sampleIndex % (sampleRate * 10)) * frequency / sampleRate
            val modDelay = centerDelay + maxMod * Math.sin(angle)
            for (c in 0 until channels) {
                buf[c][writePos] = input[c][idx]
                var readPosFloat = writePos - modDelay
                while (readPosFloat < 0) readPosFloat += maxDelaySamples
                val r0 = readPosFloat.toInt() % maxDelaySamples
                val r1 = (r0 + 1) % maxDelaySamples
                val frac = (readPosFloat - readPosFloat.toInt()).toFloat()
                input[c][idx] = buf[c][r0] * (1f - frac) + buf[c][r1] * frac
            }
            writePos = (writePos + 1) % maxDelaySamples
            sampleIndex++
        }
        downstream.process(input, offset, length)
    }

    override fun seekPerformed(requested: Long, provided: Long) {
        ringBuffer?.forEach { it.fill(0f) }
        sampleIndex = 0L
        downstream.seekPerformed(requested, provided)
    }

    override fun flush() { downstream.flush() }
    override fun close() { downstream.close() }
}

class UnderwaterFilter(
    private val downstream: FloatPcmAudioFilter,
    sampleRate: Int = 48000,
    cutoffHz: Double = 420.0,
) : FloatPcmAudioFilter {
    private val alpha: Float = run {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
        (dt / (rc + dt)).toFloat()
    }
    private var prevOutput: FloatArray? = null

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        val channels = input.size
        val prev = prevOutput?.takeIf { it.size == channels } ?: FloatArray(channels).also { prevOutput = it }
        for (i in 0 until length) {
            val idx = offset + i
            for (c in 0 until channels) {
                val out = prev[c] + alpha * (input[c][idx] - prev[c])
                prev[c] = out
                input[c][idx] = out
            }
        }
        downstream.process(input, offset, length)
    }

    override fun seekPerformed(requested: Long, provided: Long) {
        prevOutput?.fill(0f)
        downstream.seekPerformed(requested, provided)
    }

    override fun flush() { downstream.flush() }
    override fun close() { downstream.close() }
}

object AudioFilterChain {
    fun buildFactory(
        config: AudioFilterConfig,
        channel: Int?,
        onSeek: (() -> Unit)? = null,
        timescale: (() -> TimescaleParameters)? = null,
        onTimescaleOutput: ((Int, Double) -> Unit)? = null,
    ): PcmFilterFactory? {
        val bands = config.effectiveBands()
        val hasEq = bands != null && bands.any { it != 0f }
        val hasEffects = config.hasActiveEffects()
        if (channel == null && !hasEq && !hasEffects && timescale == null) {
            return null
        }

        return PcmFilterFactory { _, format, output ->
            val sampleRate = format?.sampleRate ?: 48000
            val channelCount = format?.channelCount ?: 2
            var current: FloatPcmAudioFilter = output

            if (channel != null) {
                val downstream = current
                current = object : FloatPcmAudioFilter {
                    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                        SpeakerChannels.isolate(input, offset, length, channel)
                        downstream.process(input, offset, length)
                    }
                    override fun seekPerformed(requested: Long, provided: Long) {
                        onSeek?.invoke()
                        downstream.seekPerformed(requested, provided)
                    }
                    override fun flush() { downstream.flush() }
                    override fun close() { downstream.close() }
                }
            }

            if (config.rotation && channelCount >= 2) {
                current = RotationFilter(current, sampleRate)
            }
            if (config.underwater) {
                current = UnderwaterFilter(current, sampleRate)
            }
            if (config.echo) {
                current = EchoFilter(current, sampleRate)
            }
            if (config.vibrato) {
                current = VibratoFilter(current, sampleRate)
            }
            if (config.tremolo) {
                current = TremoloFilter(current, sampleRate)
            }
            if (hasEq && Equalizer.isCompatible(format)) {
                current = Equalizer(channelCount, current, bands)
            }
            if (timescale != null || config.speed != 1.0 || config.tempo != 1.0 || config.pitchSemitones != 0.0) {
                current = TimescaleFilter(current, channelCount, sampleRate,
                    timescale ?: { TimescaleParameters(config.tempo, config.pitchSemitones, config.speed) }, onTimescaleOutput)
            }

            listOf(current)
        }
    }
}


