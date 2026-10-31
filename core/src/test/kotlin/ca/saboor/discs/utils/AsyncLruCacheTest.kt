package ca.saboor.discs.utils

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AsyncLruCacheTest {
    @Test fun `simultaneous callers share work and cancelling one waiter keeps the scan alive`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = AsyncLruCache<String, String>(scope, 2)
            val release = CompletableDeferred<Unit>()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val first = cache.load("song") { calls.incrementAndGet(); release.await(); "edges" }
            val second = cache.load("song") { error("duplicate scan") }
            assertSame(first, second)
            val waiter = launch { first.await() }
            waiter.cancelAndJoin()
            assertFalse(first.isCancelled)
            release.complete(Unit)
            assertEquals("edges", second.await())
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun `capacity evicts only the least recently used result and failed loads can retry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = AsyncLruCache<String, String>(scope, 2)
            cache.load("a") { "A" }.await()
            cache.load("b") { "B" }.await()
            assertEquals("A", cache.peek("a"))
            cache.load("c") { "C" }.await()
            assertNull(cache.peek("b"))
            assertEquals("A", cache.peek("a"))
            assertEquals("C", cache.peek("c"))
            assertNull(cache.load("missing") { null }.await())
            assertEquals("found", cache.load("missing") { "found" }.await())
        } finally { scope.cancel() }
    }
}
