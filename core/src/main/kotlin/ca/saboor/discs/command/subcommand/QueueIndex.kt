package ca.saboor.discs.command.subcommand

/** Follow the same occurrence when identical discs appear more than once. */
internal fun <T> remapQueueIndex(previous: List<T>, updated: List<T>, index: Int, current: T): Int {
    val occurrence = if (index >= 0) previous.take(index).count { it == current } else 0
    return updated.indices.filter { updated[it] == current }.getOrNull(occurrence) ?: -1
}
