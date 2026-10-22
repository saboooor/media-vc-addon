package ca.saboor.discs.screen

import java.util.Arrays

/** Exact RGB comparison avoids palette matching unchanged tiles without hash collisions. */
internal class RgbTileCache {
    private var previous: ByteArray? = null
    fun update(frame: ByteArray, frameWidth: Int, x: Int, y: Int): Boolean {
        val old = previous
        if (old != null) {
            var same = true
            for (row in 0 until 128) {
                val start = ((y + row) * frameWidth + x) * 3
                if (Arrays.mismatch(frame, start, start + 384, old, row * 384, (row + 1) * 384) >= 0) {
                    same = false
                    break
                }
            }
            if (same) return false
        }
        val target = old ?: ByteArray(128 * 128 * 3).also { previous = it }
        for (row in 0 until 128) {
            val start = ((y + row) * frameWidth + x) * 3
            frame.copyInto(target, row * 384, start, start + 384)
        }
        return true
    }
}
