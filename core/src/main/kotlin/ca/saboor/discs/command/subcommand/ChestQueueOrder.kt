package ca.saboor.discs.command.subcommand

/** Shuffle visits every slot once per pass; peek reserves the same choice for crossfade. */
class ChestQueueOrder {
    private val visited = mutableSetOf<Int>()
    private data class Choice(val index: Int, val wrapped: Boolean)
    private var reserved: Choice? = null
    private var lastShuffle: Boolean? = null
    fun reset() { visited.clear(); reserved = null }
    fun peek(size: Int, current: Int, loop: Boolean, shuffle: Boolean, removedNext: Int = 0, excluded: Set<Int> = emptySet()): Int? {
        if (size == 0) return null
        if (lastShuffle != shuffle) { reset(); lastShuffle = shuffle }
        val eligible = (0 until size).filter { it !in excluded }
        if (eligible.isEmpty()) return null
        if (!shuffle) {
            val start = if (current >= 0) current + 1 else removedNext
            return eligible.firstOrNull { it >= start } ?: if (loop) eligible.first() else null
        }
        if (current >= 0) visited.add(current)
        val remaining = eligible.filter { it !in visited }
        val wrap = remaining.isEmpty() && loop
        val candidates = if (wrap) eligible.filter { eligible.size == 1 || it != current } else remaining
        reserved?.takeIf { it.wrapped == wrap && it.index in candidates }?.let { return it.index }
        return candidates.randomOrNull().also { reserved = it?.let { Choice(it, wrap) } }
    }
    fun next(size: Int, current: Int, loop: Boolean, shuffle: Boolean, removedNext: Int = 0, excluded: Set<Int> = emptySet()): Int? {
        val index = peek(size, current, loop, shuffle, removedNext, excluded)
        if (reserved?.wrapped == true) visited.clear()
        reserved = null
        return index
    }
}
