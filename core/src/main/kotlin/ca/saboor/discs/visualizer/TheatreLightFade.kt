package ca.saboor.discs.visualizer

import kotlin.math.roundToInt

/** Three seconds for a full 15-level fade, independent of update frequency. */
class TheatreLightFade(initial: Int, private var updated: Long) {
    private var level = initial.toDouble()
    private var wasPlaying = false
    fun update(playing: Boolean, brightness: Int, now: Long): Int {
        if (playing != wasPlaying) { updated = now; wasPlaying = playing }
        val target = if (playing) 0.0 else brightness.toDouble()
        val step = (now - updated).coerceAtLeast(0L) / 3_000_000_000.0 * 15.0
        updated = now
        level = if (level < target) (level + step).coerceAtMost(target) else (level - step).coerceAtLeast(target)
        return level.roundToInt().coerceIn(0, 15)
    }
}
