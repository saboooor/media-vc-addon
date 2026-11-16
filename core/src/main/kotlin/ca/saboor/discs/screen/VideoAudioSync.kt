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

/** Drop frames to catch up without starving the screen when source decoding stays slow. */
internal class VideoFrameDelivery(private val refreshNanos: Long = 500_000_000L) {
    private var lastPresented: Long? = null

    fun shouldDrop(videoMs: Long, audioMs: Long, nowNanos: Long, hasQueuedFrames: Boolean = true): Boolean {
        val previous = lastPresented
        if (hasQueuedFrames && VideoAudioSync.stale(videoMs, audioMs) && previous != null && nowNanos - previous < refreshNanos) return true
        lastPresented = nowNanos
        return false
    }
}
