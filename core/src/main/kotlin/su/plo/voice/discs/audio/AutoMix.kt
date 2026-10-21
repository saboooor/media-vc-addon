package su.plo.voice.discs.audio

import kotlin.math.*

/** Bounded 16-second energy/onset window, fed only when AutoMix is enabled. */
class MixAnalysis(private val scanPastUncertainSections: Boolean = false) {
    private val energy = ArrayDeque<Pair<Long, Double>>()
    private var cached: MixFeatures? = null
    private var frames = 0
    private var sectionFrames = 0
    private var calculatedAt = -25
    private val tempoTracker = MixTempoTracker()
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
        sectionFrames++
        if (scanPastUncertainSections && sectionFrames >= 400) {
            sectionFrames = 0
            if (snapshot().beatMs == null) {
                // Start a fresh section so the uncertain intro cannot dilute a later rhythm.
                energy.clear()
                cached = null
                calculatedAt = frames - 25
                tempoTracker.reset()
            }
        }
    }
    @Synchronized fun reset() { energy.clear(); cached = null; frames = 0; sectionFrames = 0; calculatedAt = -25; tempoTracker.reset() }
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
        return tempoTracker.update(MixFeatures(rms, audible, beatMs, values[strongest].first, best), frames).also { cached = it }
    }
}

data class MixFeatures(val rms: Double = 0.0, val audibleStartMs: Long = 0, val beatMs: Long? = null,
                       val beatAnchorMs: Long = 0, val confidence: Double = 0.0, val mixCueMs: Long? = null)
data class MixPlan(val startMs: Long, val durationMs: Long, val cueMs: Long = 0,
                   val incomingGain: Double = 1.0, val beatAligned: Boolean = false, val tempoFactor: Double = 1.0, val earlyExit: Boolean = false) {
    val readyDescription: String get() {
        val type = if (beatAligned) "Tempo ramp + filter sweep + bass swap ✨" else "Smooth fade"
        val seconds = String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1000.0)
        val cue = if (cueMs >= 1000) " · next at ${cueMs / 60000}:${(cueMs / 1000 % 60).toString().padStart(2, '0')}" else ""
        val exit = if (earlyExit) " · mix out at ${startMs / 60000}:${(startMs / 1000 % 60).toString().padStart(2, '0')}" else ""
        return "AutoMix ready: $type · ${seconds}s$cue$exit"
    }
}

/** Changing tempo (or losing the beat) needs two seconds of consistent audio evidence. */
internal class MixTempoTracker {
    private var stable: MixFeatures? = null
    private var candidateBeat: Long? = null
    private var candidateSince = 0
    private var pending = false

    fun reset() { stable = null; pending = false }

    fun update(raw: MixFeatures, frames: Int): MixFeatures {
        val previous = stable
        if (previous?.beatMs == null || raw.beatMs == previous.beatMs) {
            stable = raw
            pending = false
            return raw
        }
        if (!pending || candidateBeat != raw.beatMs) {
            candidateBeat = raw.beatMs
            candidateSince = frames
            pending = true
        }
        if (frames - candidateSince >= 100) {
            stable = raw
            pending = false
            return raw
        }
        // Energy and silence information stay current; only tempo/phase retain the stable estimate.
        return raw.copy(beatMs = previous.beatMs, beatAnchorMs = previous.beatAnchorMs,
            confidence = previous.confidence)
    }
}

object AutoMix {
    /** Allow later cues for modest quality improvements while retaining the earlier cue on ties. */
    fun chooseSection(outgoing: MixFeatures, sections: List<MixFeatures>, nextDurationMs: Long,
                      incomingBaseRate: Double = 1.0): MixFeatures? {
        val first = sections.firstOrNull() ?: return null
        val beat = outgoing.beatMs ?: return first
        if (outgoing.confidence < 0.55) return first
        var chosen: MixFeatures? = null
        var best = Double.NEGATIVE_INFINITY
        for (section in sections) {
            val nextBeat = section.beatMs ?: continue
            val tempo = nextBeat.toDouble() / beat / incomingBaseRate
            val cue = section.mixCueMs ?: section.audibleStartMs
            val blendMs = minOf(beat * 16, 16_000L)
            if (section.confidence < 0.55 || tempo !in 0.92..1.08 ||
                nextDurationMs > 0 && cue + blendMs * incomingBaseRate * tempo >= nextDurationMs) continue
            val energyMatch = if (outgoing.rms > 0.003 && section.rms > 0.003)
                minOf(outgoing.rms, section.rms) / maxOf(outgoing.rms, section.rms) else 0.0
            val score = section.confidence * 0.60 + (1.0 - abs(tempo - 1.0) / 0.08) * 0.25 +
                energyMatch * 0.15 - cue / 60_000.0 * 0.03
            if (chosen == null || score > best + 0.02) { chosen = section; best = score }
        }
        return chosen ?: first
    }

    fun plan(durationMs: Long, nextDurationMs: Long, outgoing: MixFeatures, incoming: MixFeatures?, outgoingRate: Double = 1.0, incomingBaseRate: Double = 1.0): MixPlan {
        val next = incoming ?: MixFeatures()
        val beat = outgoing.beatMs
        val tempoFactor = if (beat != null && next.beatMs != null) next.beatMs.toDouble() / beat / incomingBaseRate else 1.0
        val compatible = beat != null && next.beatMs != null && outgoing.confidence >= 0.55 && next.confidence >= 0.55 &&
            tempoFactor in 0.92..1.08
        val limit = min(if (compatible) 16_000L else 10_000L, min(durationMs / 3, nextDurationMs.takeIf { it > 0 }?.div(3) ?: 16_000L)).coerceAtLeast(250L)
        val desired = if (compatible) beat!! * 16 else if (outgoing.rms < 0.025 || next.rms < 0.025) 2_000L else 4_000L
        val fade = desired.coerceIn(250L, limit)
        var start = (durationMs - (fade * outgoingRate).toLong()).coerceAtLeast(0)
        if (compatible) start = (start - Math.floorMod(start - outgoing.beatAnchorMs,
            (beat!! * outgoingRate).toLong().coerceAtLeast(1))).coerceAtLeast(0)
        // A later confident section can explicitly cue past an uncertain musical intro.
        val cue = if (compatible) next.mixCueMs ?: (next.audibleStartMs +
            Math.floorMod(next.beatAnchorMs - next.audibleStartMs, next.beatMs!!)) else next.audibleStartMs
        val gain = if (outgoing.rms > 0.003 && next.rms > 0.003) (outgoing.rms / next.rms).coerceIn(0.5, 1.0) else 1.0
        val cueLimit = if (nextDurationMs > 0)
            (nextDurationMs - ceil(fade * incomingBaseRate * (if (compatible) tempoFactor else 1.0)).toLong() - 1).coerceIn(0L, 60_000L)
            else 60_000L
        return MixPlan(start, min(fade, ((durationMs - start) / outgoingRate).toLong()).coerceAtLeast(1), cue.coerceIn(0, cueLimit), gain,
            compatible, if (compatible) tempoFactor else 1.0)
    }
    /** Use a strong section near the ending rather than waiting through a potentially weaker outro. */
    fun earlierExit(plan: MixPlan, durationMs: Long, positionMs: Long, outgoing: MixFeatures,
                    incoming: MixFeatures?, rate: Double = 1.0, incomingBaseRate: Double = 1.0): MixPlan {
        if (!plan.beatAligned || plan.earlyExit || durationMs < 60_000 || incoming == null) return plan
        val beat = outgoing.beatMs ?: return plan
        val nextBeat = incoming.beatMs ?: return plan
        val earliest = maxOf(durationMs * 2 / 3, durationMs - 45_000)
        if (positionMs < earliest || outgoing.confidence < 0.8 || incoming.confidence < 0.8) return plan
        val match = nextBeat.toDouble() / beat / incomingBaseRate
        if (abs(match - plan.tempoFactor) > 0.02 || abs(match - 1.0) > 0.04) return plan
        val loud = maxOf(outgoing.rms, incoming.rms)
        val quiet = minOf(outgoing.rms, incoming.rms)
        if (quiet <= 0.003 || quiet / loud < 0.65) return plan
        val beatOnTrack = (beat * rate).toLong().coerceAtLeast(1)
        val lead = positionMs + beatOnTrack * 4
        val start = lead + Math.floorMod(outgoing.beatAnchorMs - lead, beatOnTrack)
        val end = start + (plan.durationMs * rate).toLong()
        // Keep the full blend and save at least four seconds versus the planned end transition.
        if (end > plan.startMs - 4_000 || start >= plan.startMs) return plan
        return plan.copy(startMs = start, earlyExit = true)
    }

    fun releaseTempo(start: Double, elapsedMs: Long): Double {
        val t = (elapsedMs / 4_000.0).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return start + (1.0 - start) * eased
    }
    fun gains(progress: Double, outgoingStart: Double = 1.0, incomingTarget: Double = 1.0): Pair<Double, Double> {
        val angle = progress.coerceIn(0.0, 1.0) * Math.PI / 2
        return outgoingStart * cos(angle) to incomingTarget * sin(angle)
    }
}

/** Monotonic transmitted-audio clock: pause, buffering, and seeks cannot jump a fade. */
class MixEnvelope(val durationMs: Long, private val outgoingStart: Double = 1.0, val incomingTarget: Double = 1.0,
                  val beatMatched: Boolean = false, incomingTempoMatch: Double = 1.0,
                  outgoingTempoStart: Double = 1.0) {
    private val tempo = MixTempoEnvelope(incomingTempoMatch, outgoingTempoStart, durationMs)
    fun tempos(sentAudioMs: Long): Pair<Double, Double> = if (beatMatched) tempo.factors(sentAudioMs) else 1.0 to 1.0
    private fun progress(sentAudioMs: Long) = (sentAudioMs.toDouble() / durationMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    fun gains(sentAudioMs: Long): Pair<Double, Double> {
        val t = progress(sentAudioMs)
        // Bring the new track up early, hold both at equal power, then release the old track.
        val ramp = if (beatMatched) 0.30 else 0.40
        fun ease(value: Double): Double {
            val x = value.coerceIn(0.0, 1.0)
            return x * x * (3.0 - 2.0 * x)
        }
        val shaped = when {
            t < ramp -> 0.5 * ease(t / ramp)
            t > 1.0 - ramp -> 0.5 + 0.5 * ease((t - (1.0 - ramp)) / ramp)
            else -> 0.5
        }
        return AutoMix.gains(shaped, outgoingStart, incomingTarget)
    }
    /** Outgoing low-pass and incoming high-pass cutoffs; zero means no automatic filter. */
    fun passCutoffs(sentAudioMs: Long): Pair<Double, Double> {
        if (!beatMatched) return 0.0 to 0.0
        val t = progress(sentAudioMs)
        fun ease(x: Double): Double { val v = x.coerceIn(0.0, 1.0); return v * v * (3 - 2 * v) }
        val out = ease((t - 0.4) / 0.6)
        val incoming = ease(t / 0.6)
        val low = if (t <= 0.4) 0.0 else 20000.0 * kotlin.math.exp(kotlin.math.ln(1200.0 / 20000.0) * out)
        val high = if (t >= 0.6) 0.0 else 800.0 * kotlin.math.exp(kotlin.math.ln(20.0 / 800.0) * incoming)
        return low to high
    }
    fun bassGains(sentAudioMs: Long): Pair<Double, Double> {
        if (!beatMatched) return 1.0 to 1.0
        val t = ((progress(sentAudioMs) - 0.35) / 0.30).coerceIn(0.0, 1.0)
        val incoming = t * t * (3.0 - 2.0 * t)
        return (1.0 - incoming) to incoming
    }
    fun complete(sentAudioMs: Long) = sentAudioMs >= durationMs
}

/** Once advertised, the same transition plan is used until its playback context changes. */
internal class MixPlanCommitment<K> {
    private var key: K? = null
    private var committed: MixPlan? = null
    fun clear() { key = null; committed = null }
    fun resolve(context: K?, candidate: MixPlan): MixPlan {
        if (context == null) { clear(); return candidate }
        if (context != key) { key = context; committed = null }
        if (committed == null && candidate.beatAligned) committed = candidate
        else if (candidate.earlyExit && committed?.earlyExit == false && candidate.cueMs == committed?.cueMs &&
            candidate.tempoFactor == committed?.tempoFactor) committed = candidate
        return committed ?: candidate
    }
}

/** Both tracks remain tempo-matched while moving toward a shared geometric-mean tempo. */
internal class MixTempoEnvelope(private val incomingMatch: Double, private val outgoingStart: Double,
                               private val durationMs: Long) {
    private val lower = maxOf(0.92 / outgoingStart, 0.92 / incomingMatch)
    private val upper = minOf(1.08 / outgoingStart, 1.08 / incomingMatch)
    private val sharedAdjustment = (1.0 / sqrt(incomingMatch)).coerceIn(lower, upper)
    fun factors(sentAudioMs: Long): Pair<Double, Double> {
        // Finish the ramp before the bass handover, then keep both tempos steady.
        val t = (sentAudioMs.toDouble() / (durationMs * 0.35).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        val adjustment = 1.0 + (sharedAdjustment - 1.0) * eased
        return outgoingStart * adjustment to incomingMatch * adjustment
    }
}
