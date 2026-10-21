package su.plo.voice.discs.audio

import kotlin.math.*

/** Bounded 16-second energy/onset window, fed only when AutoMix is enabled. */
class MixAnalysis {
    private val energy = ArrayDeque<Pair<Long, Double>>()
    private var cached: MixFeatures? = null
    private var frames = 0
    private var calculatedAt = -25
    @Synchronized fun accept(channels: Array<FloatArray>, offset: Int, length: Int, positionMs: Long, scale: Double = 1.0) {
        if (channels.isEmpty() || length == 0) return
        var sum = 0.0
        for (channel in channels) for (i in offset until offset + length) sum += channel[i].toDouble().pow(2)
        add(positionMs, sqrt(sum / (length * channels.size)) * scale)
    }
    @Synchronized fun add(positionMs: Long, rms: Double) {
        energy.addLast(positionMs to rms.coerceIn(0.0, 1.0))
        while (energy.size > 800) energy.removeFirst()
        frames++
    }
    @Synchronized fun reset() { energy.clear(); cached = null; frames = 0; calculatedAt = -25 }
    @Synchronized fun snapshot(): MixFeatures {
        if (frames - calculatedAt < 25 && cached != null) return cached!!
        calculatedAt = frames
        val values = energy.toList()
        if (values.isEmpty()) return MixFeatures()
        val rms = sqrt(values.map { it.second * it.second }.average())
        val audible = values.firstOrNull { it.second > 0.003 }?.first ?: 0L
        // Positive energy differences emphasize drum onsets rather than sustained notes.
        val onsets = DoubleArray(values.size) { i -> if (i == 0) 0.0 else max(0.0, values[i].second - values[i - 1].second) }
        var best = 0.0
        var lag = 0
        if (values.size >= 150 && rms > 0.003) for (candidate in 17..50) {
            var dot = 0.0; var a = 0.0; var b = 0.0
            for (i in candidate until onsets.size) {
                dot += onsets[i] * onsets[i - candidate]
                a += onsets[i] * onsets[i]; b += onsets[i - candidate] * onsets[i - candidate]
            }
            val correlation = if (a * b > 1e-12) dot / sqrt(a * b) else 0.0
            if (correlation > best + 0.02) { best = correlation; lag = candidate }
        }
        val beatMs = if (lag > 0 && best >= 0.55) lag * 20L else null
        val strongest = onsets.indices.maxByOrNull { onsets[it] } ?: 0
        return MixFeatures(rms, audible, beatMs, values[strongest].first, best).also { cached = it }
    }
}

data class MixFeatures(val rms: Double = 0.0, val audibleStartMs: Long = 0, val beatMs: Long? = null,
                       val beatAnchorMs: Long = 0, val confidence: Double = 0.0)
data class MixPlan(val startMs: Long, val durationMs: Long, val cueMs: Long = 0,
                   val incomingGain: Double = 1.0, val beatAligned: Boolean = false)

object AutoMix {
    fun plan(durationMs: Long, nextDurationMs: Long, outgoing: MixFeatures, incoming: MixFeatures?): MixPlan {
        val next = incoming ?: MixFeatures()
        val beat = outgoing.beatMs
        val compatible = beat != null && next.beatMs != null && outgoing.confidence >= 0.55 && next.confidence >= 0.55 &&
            abs(beat.toDouble() / next.beatMs - 1.0) <= 0.08
        val limit = min(10_000L, min(durationMs / 3, nextDurationMs.takeIf { it > 0 }?.div(3) ?: 10_000L)).coerceAtLeast(250L)
        val desired = if (compatible) beat!! * 8 else if (outgoing.rms < 0.025 || next.rms < 0.025) 2_000L else 4_000L
        val fade = desired.coerceIn(250L, limit)
        var start = (durationMs - fade).coerceAtLeast(0)
        if (compatible) start = (start - Math.floorMod(start - outgoing.beatAnchorMs, beat!!)).coerceAtLeast(0)
        // Trim only silence found in the bounded intro; retain audible musical introductions.
        val cue = if (compatible) next.beatAnchorMs.takeIf { it in next.audibleStartMs..(next.audibleStartMs + next.beatMs!!) }
            ?: next.audibleStartMs else next.audibleStartMs
        val gain = if (outgoing.rms > 0.003 && next.rms > 0.003) (outgoing.rms / next.rms).coerceIn(0.5, 1.0) else 1.0
        return MixPlan(start, min(fade, durationMs - start).coerceAtLeast(1), cue.coerceIn(0, 8_000), gain, compatible)
    }
    fun gains(progress: Double, outgoingStart: Double = 1.0, incomingTarget: Double = 1.0): Pair<Double, Double> {
        val angle = progress.coerceIn(0.0, 1.0) * Math.PI / 2
        return outgoingStart * cos(angle) to incomingTarget * sin(angle)
    }
}

/** Monotonic transmitted-audio clock: pause, buffering, and seeks cannot jump a fade. */
class MixEnvelope(val durationMs: Long, private val outgoingStart: Double = 1.0, val incomingTarget: Double = 1.0) {
    fun gains(sentAudioMs: Long) = AutoMix.gains(sentAudioMs.toDouble() / durationMs.coerceAtLeast(1), outgoingStart, incomingTarget)
    fun complete(sentAudioMs: Long) = sentAudioMs >= durationMs
}
