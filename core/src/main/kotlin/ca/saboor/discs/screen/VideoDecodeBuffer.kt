package ca.saboor.discs.screen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** A shared buffer pool bounds all queued, decoding and presenting RGB48 frames together. */
internal class VideoDecodeBuffer(frameBytes: Int, budgetBytes: Int = 96 * 1024 * 1024) {
    val capacity: Int
    private val free: Channel<ByteArray>
    val frames: Channel<Frame>
    @Volatile var waitingForBuffer: Boolean = false
        private set
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val hasQueuedFrames: Boolean get() = !frames.isEmpty
    data class Frame(val pixels: ByteArray, val timestampMs: Long)

    init {
        require(frameBytes > 0 && budgetBytes >= frameBytes * 2L) { "Decode buffer must fit two frames" }
        // Keep a modest jitter buffer within the memory budget without building an unbounded queue.
        capacity = (budgetBytes / frameBytes).coerceIn(2, 12)
        free = Channel(capacity)
        frames = Channel(capacity)
        repeat(capacity) { free.trySend(ByteArray(frameBytes)).getOrThrow() }
    }

    suspend fun decode(read: suspend (ByteArray) -> Long?) {
        try {
            while (true) {
                waitingForBuffer = true
                val pixels = try { free.receive() } finally { waitingForBuffer = false }
                val timestamp = read(pixels) ?: break
                waitingForBuffer = true
                try { frames.send(Frame(pixels, timestamp)) } finally { waitingForBuffer = false }
            }
        } catch (e: Exception) {
            frames.close(e)
            if (e is CancellationException) throw e
        } finally {
            waitingForBuffer = false
            frames.close()
        }
    }

    fun release(frame: Frame) { check(free.trySend(frame.pixels).isSuccess) { "Frame released twice" } }
}
