package ca.saboor.discs.visualizer

internal data class LightPulse(val lit: Boolean = false, val beatCount: Int = 0, val isBuzzing: Boolean = false, val colorStep: Int = 0)

/** Frozen before scheduling chunk updates, so every lamp samples the same playback tick. */
internal class LightBandFrame(
    private val history: List<LightPulse>,
    private val playing: Boolean,
    private val ripple: Boolean,
    private val chase: Boolean,
    private val rippleSpeed: Double = 1.0,
    private val chaseSpeed: Double = 1.0,
    private val tickCount: Int = 0,
) {
    fun sample(distance: Double, parity: Int): LightPulse {
        val delay = if (ripple) (distance / (3.0 * rippleSpeed.coerceIn(0.25, 4.0))).toInt().coerceIn(0, 12) else 0
        val pulse = history.getOrNull(delay.coerceAtMost((history.size - 1).coerceAtLeast(0))) ?: LightPulse()
        val speed = chaseSpeed.coerceIn(0.25, 4.0)
        val chaseParity = if (speed <= 1.0) {
            (pulse.beatCount * speed).toInt() and 1
        } else {
            val stepTicks = (8.0 / speed).toInt().coerceAtLeast(1)
            (pulse.beatCount + tickCount / stepTicks) and 1
        }
        val lit = when {
            !playing -> false
            !chase -> pulse.lit
            pulse.isBuzzing -> if (parity == 0) pulse.lit else !pulse.lit
            else -> pulse.lit && chaseParity == parity
        }
        return pulse.copy(lit = lit, colorStep = pulse.colorStep + if (chase) parity else 0)
    }
}
