package su.plo.voice.discs.screen

import java.util.Collections
import java.util.IdentityHashMap

/** Changed frames go to everyone; identical frames only to arrivals or on periodic refresh. */
internal class FrameRecipients<T>(private val refreshNanos: Long = 2_000_000_000L) {
    private var previous: Set<T> = emptySet()
    private var frame: ByteArray? = null
    private var lastRefresh = 0L
    fun select(encoded: ByteArray, viewers: Collection<T>, now: Long): List<T> {
        val all = frame !== encoded || now - lastRefresh >= refreshNanos
        val recipients = if (all) viewers.toList() else viewers.filterNot { it in previous }
        previous = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).also { it.addAll(viewers) }
        frame = encoded
        if (all && viewers.isNotEmpty()) lastRefresh = now
        return recipients
    }
    fun clear() { previous = emptySet(); frame = null; lastRefresh = 0 }
}
