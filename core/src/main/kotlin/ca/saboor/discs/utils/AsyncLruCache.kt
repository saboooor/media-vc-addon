package ca.saboor.discs.utils

import kotlinx.coroutines.*

/** Shared loads outlive individual waiters; only completed values count toward the LRU limit. */
internal class AsyncLruCache<K, V : Any>(private val scope: CoroutineScope, private val capacity: Int) {
    init { require(capacity > 0) }
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private val loading = mutableMapOf<K, Deferred<V?>>()

    @Synchronized fun peek(key: K): V? = values[key]

    @Synchronized fun load(key: K, loader: suspend () -> V?): Deferred<V?> {
        values[key]?.let { return CompletableDeferred(it) }
        loading[key]?.takeUnless { it.isCompleted }?.let { return it }
        val job = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loader()?.also { value ->
                synchronized(this@AsyncLruCache) {
                    values[key] = value
                    while (values.size > capacity) values.remove(values.keys.first())
                }
            }
        }
        loading[key] = job
        job.invokeOnCompletion { synchronized(this) { if (loading[key] === job) loading.remove(key) } }
        job.start()
        return job
    }
}
