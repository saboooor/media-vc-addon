package su.plo.voice.discs.visualizer

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
        snapshot = snapshot.map { if (it.id == id) change(it) else it }
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
    private val envelope = SpeakerEnvelope()
    var lit = false
        private set
    fun update(bass: Float): Boolean {
        val pulse = envelope.update(bass)
        lit = if (lit) pulse > 0.2f else pulse > 0.4f
        return lit
    }
}
