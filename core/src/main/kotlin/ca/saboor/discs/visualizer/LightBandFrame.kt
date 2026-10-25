package ca.saboor.discs.visualizer

internal data class LightPulse(val lit: Boolean = false, val beatCount: Int = 0, val isBuzzing: Boolean = false, val colorStep: Int = 0)

/** Frozen before scheduling chunk updates, so every lamp samples the same playback tick. */
internal class LightBandFrame(
    private val history: List<LightPulse>,
    private val playing: Boolean,
    private val ripple: Boolean,
    private val chase: Boolean,
) {
    fun sample(distance: Double, parity: Int): LightPulse {
        val delay = if (ripple) (distance / 3.0).toInt().coerceIn(0, 12) else 0
        val pulse = history.getOrNull(delay.coerceAtMost((history.size - 1).coerceAtLeast(0))) ?: LightPulse()
        val lit = when {
            !playing -> false
            !chase -> pulse.lit
            pulse.isBuzzing -> if (parity == 0) pulse.lit else !pulse.lit
            else -> pulse.lit && (pulse.beatCount and 1) == parity
        }
        return pulse.copy(lit = lit, colorStep = pulse.colorStep + if (chase) parity else 0)
    }
}
