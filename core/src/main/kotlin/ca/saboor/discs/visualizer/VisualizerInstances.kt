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
    private var sustained = 0f
    var lit = false
        private set
    fun update(bass: Float, falloff: Double = 0.2): Boolean {
        val value = bass.coerceIn(0f, 1f)
        // Invalid configuration falls back to the original response; endpoints are intentional.
        val retention = if (falloff.isFinite()) falloff.coerceIn(0.0, 1.0).toFloat() else 0.2f
        sustained = when {
            value >= 0.35f -> (sustained * 0.6f + value * 0.4f).coerceIn(0f, 1f)
            value >= 0.22f -> sustained * 0.75f
            else -> 0f
        }
        val transient = (value - previous).coerceAtLeast(0f) * 3f
        val hold = max(value * 0.6f, if (sustained >= 0.38f) sustained * 1.05f else sustained * 0.7f)
        pulse = max(max(transient, pulse * retention), hold).coerceIn(0f, 1f)
        previous = value
        lit = if (lit) pulse > 0.2f else pulse > 0.4f
        return lit
    }
}
