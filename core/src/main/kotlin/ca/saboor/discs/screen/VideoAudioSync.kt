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

/** Skip obsolete queued frames whenever audio is ahead and a newer frame is available. */
internal class VideoFrameDelivery(private val refreshNanos: Long = 40_000_000L) {
    fun shouldDrop(videoMs: Long, audioMs: Long, nowNanos: Long, hasQueuedFrames: Boolean = true): Boolean {
        if (VideoAudioSync.stale(videoMs, audioMs) && hasQueuedFrames) return true
        // If there is no replacement queued, present this frame rather than starving the screen.
        return false
    }
}
