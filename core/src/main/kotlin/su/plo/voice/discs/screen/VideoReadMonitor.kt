package su.plo.voice.discs.screen

/** Tracks blocked frame reads without counting pause time or frame rendering work. */
internal class VideoReadMonitor(private val timeoutNanos: Long) {
    @Volatile private var readStarted: Long? = null

    @Synchronized
    fun beginRead(now: Long) { readStarted = now }
    @Synchronized
    fun endRead() { readStarted = null }

    @Synchronized
    fun isStalled(now: Long, paused: Boolean): Boolean {
        val started = readStarted ?: return false
        if (paused) {
            readStarted = now
            return false
        }
        return now - started >= timeoutNanos
    }
}
