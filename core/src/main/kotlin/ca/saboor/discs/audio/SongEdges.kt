package ca.saboor.discs.audio

internal data class SongEdges(val startMs: Long = 0L, val endMs: Long)

/** Conservative frame-level bounds: never infer a silent tail from an incomplete scan. */
internal class EdgeSilenceScan(private val durationMs: Long, val limitMs: Long = 15_000L) {
    private var leadingEnd = 0L
    var headComplete = false
        private set
    private val tailStart = (durationMs - limitMs).coerceAtLeast(0L)
    private var firstTail: Long? = null
    private var lastTail = -1L
    private var lastAudibleEnd = tailStart

    fun head(timeMs: Long, silent: Boolean) {
        if (headComplete) return
        if (!silent) headComplete = true
        else leadingEnd = (timeMs + 20).coerceAtMost(limitMs)
        if (timeMs + 20 >= limitMs) headComplete = true
    }
    fun tail(timeMs: Long, silent: Boolean) {
        if (timeMs < tailStart - 40) return
        if (firstTail == null) firstTail = timeMs
        lastTail = maxOf(lastTail, timeMs + 20)
        if (!silent) lastAudibleEnd = timeMs + 20
    }
    fun result(): SongEdges {
        val start = if (headComplete && leadingEnd >= 100) leadingEnd else 0L
        val completeTail = firstTail?.let { it <= tailStart + 100 } == true && lastTail >= durationMs - 100
        val end = if (completeTail && durationMs - lastAudibleEnd >= 100) lastAudibleEnd.coerceAtMost(durationMs) else durationMs
        return if (end > start) SongEdges(start, end) else SongEdges(endMs = durationMs)
    }
}
