package ca.saboor.discs.screen

import java.util.concurrent.TimeUnit

/** Tracks blocked frame reads without counting pause, audio-sync wait, or frame rendering time. */
internal class VideoReadMonitor(@Volatile var timeoutNanos: Long) {
    @Volatile private var readStarted: Long? = null
    val timeoutSeconds: Long get() = TimeUnit.NANOSECONDS.toSeconds(timeoutNanos).coerceAtLeast(1)

    @Synchronized
    fun beginRead(now: Long) { readStarted = now }
    @Synchronized
    fun endRead() { readStarted = null }

    @Synchronized
    fun isStalled(now: Long, waiting: Boolean): Boolean {
        val started = readStarted ?: return false
        if (waiting) {
            readStarted = now
            return false
        }
        return now - started >= timeoutNanos
    }
}
