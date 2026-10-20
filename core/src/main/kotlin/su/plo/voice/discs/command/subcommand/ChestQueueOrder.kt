package su.plo.voice.discs.command.subcommand

/** Shuffle visits every slot once per pass; peek reserves the same choice for crossfade. */
class ChestQueueOrder {
    private val visited = mutableSetOf<Int>()
    private data class Choice(val index: Int, val wrapped: Boolean)
    private var reserved: Choice? = null
    private var lastShuffle: Boolean? = null
    fun reset() { visited.clear(); reserved = null }
    fun peek(size: Int, current: Int, loop: Boolean, shuffle: Boolean, removedNext: Int = 0): Int? {
        if (size == 0) return null
        if (lastShuffle != shuffle) { reset(); lastShuffle = shuffle }
        if (!shuffle) return (if (current >= 0) current + 1 else removedNext).let {
            if (it < size) it else if (loop) 0 else null
        }
        if (current >= 0) visited.add(current)
        val remaining = (0 until size).filter { it !in visited }
        val wrap = remaining.isEmpty() && loop
        val candidates = if (wrap) (0 until size).filter { size == 1 || it != current } else remaining
        reserved?.takeIf { it.wrapped == wrap && it.index in candidates }?.let { return it.index }
        return candidates.randomOrNull().also { reserved = it?.let { Choice(it, wrap) } }
    }
    fun next(size: Int, current: Int, loop: Boolean, shuffle: Boolean, removedNext: Int = 0): Int? {
        val index = peek(size, current, loop, shuffle, removedNext)
        if (reserved?.wrapped == true) visited.clear()
        reserved = null
        return index
    }
}
