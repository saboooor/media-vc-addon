package ca.saboor.discs.visualizer

import kotlin.math.max

enum class VisualizerKind(val label: String) { BARS("Frequency bars"), SPEAKER("Speaker driver") }
enum class VisualizerFacing(val degrees: Float) { SOUTH(0f), EAST(90f), NORTH(180f), WEST(270f) }
data class VisualizerInstance(val id: Int, val kind: VisualizerKind = VisualizerKind.BARS,
    val enabled: Boolean = false, val layout: VisualizerLayout = VisualizerLayout(), val facing: VisualizerFacing = VisualizerFacing.SOUTH)

/** Immutable snapshots keep per-player dialog selection independent from other viewers. */
class VisualizerInstances {
    private var nextId = 2
    @Volatile var snapshot = listOf(VisualizerInstance(1))
        private set
    @Synchronized fun restore(instances: List<VisualizerInstance>) {
        snapshot = instances.toList()
        nextId = (instances.maxOfOrNull { it.id } ?: 0) + 1
    }
    @Synchronized fun add(kind: VisualizerKind): Int? {
        if (snapshot.size >= 8) return null
        val id = nextId++
        snapshot = snapshot + VisualizerInstance(id, kind, true, VisualizerLayout(x = ((snapshot.size % 4) * 3).toFloat(), z = ((snapshot.size / 4) * 3).toFloat()))
        return id
    }
    @Synchronized fun update(id: Int, change: (VisualizerInstance) -> VisualizerInstance) {
        val current = snapshot
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = change(current[index])
        if (updated == current[index]) return
        snapshot = current.toMutableList().also { it[index] = updated }
    }
    @Synchronized fun remove(id: Int) { snapshot = snapshot.filterNot { it.id == id } }
}

/** Fast bass-transient attack with a short release rather than a permanently expanded square. */
class SpeakerEnvelope {
    private var previous = 0f
    private var pulse = 0f
    fun update(bass: Float): Float {
        val value = bass.coerceIn(0f, 1f)
        pulse = max(max((value - previous).coerceAtLeast(0f) * 3f, pulse * 0.65f), value * 0.25f).coerceIn(0f, 1f)
        previous = value
        return pulse
    }
}

/** Hysteresis avoids rebuilding world lighting for tiny fluctuations around the trigger. */
class BassLampEnvelope {
    private var previous = 0f
    private var pulse = 0f
    private var baseline = 0f
    private var sustained = 0f
    private var sustainTicks = 0
    private var rollingPeak = 0.80f
    var beatCount = 0
        private set
    var isBuzzing = false
        private set
    var lit = false
        private set
    fun update(bass: Float, falloff: Double = 0.2): Boolean {
        val raw = bass.coerceIn(0f, 1f)
        if (raw > rollingPeak) {
            rollingPeak = rollingPeak * 0.4f + raw * 0.6f
        } else if (raw > 0.02f) {
            // Very slow peak decay (~0.985 per 50ms tick) so rollingPeak stays anchored to true peaks
            // rather than dropping to the average background level between beats.
            rollingPeak = (rollingPeak * 0.985f + raw * 0.015f).coerceIn(0.35f, 1.0f)
        }
        val value = if (raw <= 0.02f) raw
        else (raw / rollingPeak.coerceAtLeast(0.35f) * 0.80f).coerceAtLeast(raw).coerceIn(0f, 1f)

        // Slow baseline tracks steady background music energy so normal songs still turn off between beats,
        // while genuine high-energy sustained sections (long bass sub-drops / sustained synths) stay lit.
        baseline = if (value < baseline) baseline * 0.65f + value * 0.35f
        else baseline * 0.92f + value * 0.08f

        // Invalid configuration falls back to the original response; endpoints are intentional.
        val retention = if (falloff.isFinite()) falloff.coerceIn(0.0, 1.0).toFloat() else 0.2f
        val transient = (value - previous).coerceAtLeast(0f) * 3f
        val prominentHold = raw >= 0.46f && value >= 0.56f && (value >= baseline * 0.92f)
        sustained = when {
            prominentHold -> (sustained * 0.55f + value * 0.45f).coerceIn(0f, 1f)
            value >= 0.42f -> sustained * 0.60f
            else -> 0f
        }
        sustainTicks = if (prominentHold) sustainTicks + 1 else 0

        val wasLit = lit
        val veryLoudSustain = sustainTicks >= 3 && raw >= 0.72f && value >= 0.80f && sustained >= 0.72f
        isBuzzing = veryLoudSustain && transient < 0.45f
        if (isBuzzing) {
            // Buzz/flicker only when a sustained section is really, really loud;
            // ordinary sustained bass/highs stay solidly lit below.
            val rising = value > previous + 0.03f
            lit = !lit || rising
            pulse = if (lit) max(sustained, value * 0.6f).coerceAtLeast(0.45f) else 0.15f
        } else {
            val hold = if (sustainTicks >= 2 && sustained >= 0.52f) {
                sustained * 0.78f
            } else {
                (value - baseline * 0.6f).coerceAtLeast(0f) * 0.5f
            }
            pulse = max(max(transient, pulse * retention), hold).coerceIn(0f, 1f)
            lit = if (lit) pulse > 0.22f else pulse > 0.40f
        }
        if (lit && (!wasLit || (sustainTicks <= 1 && transient >= 0.40f))) {
            beatCount++
        }
        previous = value
        return lit
    }
}
