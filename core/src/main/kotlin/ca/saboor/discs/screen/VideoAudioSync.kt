package ca.saboor.discs.screen

/** Audio is the master clock; retain a small tolerance for frame boundaries. */
internal object VideoAudioSync {
    fun waitMillis(videoMs: Long, audioMs: Long): Long = (videoMs - audioMs - 40).coerceIn(0, 50)
    fun stale(videoMs: Long, audioMs: Long): Boolean = audioMs - videoMs > 200
    fun timestamp(line: String): Long? = if (line.contains("showinfo"))
        pts.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { (it * 1000).toLong() }
        else null
    private val pts = Regex("\\bpts_time:([^\\s]+)")
}

/** Drop obsolete queued frames without imposing a separate low-FPS catch-up mode. */
internal class VideoFrameDelivery(private val refreshNanos: Long = 40_000_000L) {
    private var lastPresented: Long? = null

    fun shouldDrop(videoMs: Long, audioMs: Long, nowNanos: Long, hasQueuedFrames: Boolean = true): Boolean {
        val previous = lastPresented
        if (VideoAudioSync.stale(videoMs, audioMs) && hasQueuedFrames && previous != null &&
            nowNanos - previous < refreshNanos) return true
        // If there is no replacement queued, present this frame rather than starving the screen.
        lastPresented = nowNanos
        return false
    }
}
