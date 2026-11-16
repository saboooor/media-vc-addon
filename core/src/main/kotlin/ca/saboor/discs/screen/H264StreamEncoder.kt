package ca.saboor.discs.screen

import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Splits Annex B access units at AUD boundaries, independent of pipe read boundaries. */
internal class H264AccessUnits(private val emit: (ByteArray, Boolean) -> Unit) {
    private var buf = ByteArray(16384)
    private var size = 0
    private var zeros = 0
    private var header = false
    private var prefix = 0
    private var idr = false

    private fun append(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }

    fun accept(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        val end = offset + length
        for (i in offset until end) accept(buffer[i].toInt())
    }

    fun accept(value: Int) {
        val b = value and 255
        append(b)
        require(size <= 4 * 1024 * 1024 + 5) { "Oversized H.264 access unit" }
        if (header) {
            if (b and 31 == 9) {
                val end = size - prefix - 1
                if (end > 0) emit(buf.copyOf(end), idr)
                buf[0] = 0; buf[1] = 0; buf[2] = 0; buf[3] = 1; buf[4] = b.toByte()
                size = 5
                idr = false
            } else if (b and 31 == 5) idr = true
            header = false
        }
        if (b == 1 && zeros >= 2) { header = true; prefix = if (zeros >= 3) 4 else 3 }
        zeros = if (b == 0) zeros + 1 else 0
    }

    fun finish() {
        if (size > 0) emit(buf.copyOf(size), idr)
        size = 0
    }
}

/** Native low-latency encoder with three reusable input buffers and one pending raw frame. */
internal class H264StreamEncoder(
    ffmpeg: String, val width: Int, val height: Int, val fps: Int, bitrateKbps: Int,
    private val output: (H264StreamEncoder, Int, ByteArray, Boolean) -> Unit,
) : AutoCloseable {
    val streamId = streams.incrementAndGet()
    val encodedWidth = (width + 1) and -2
    val encodedHeight = (height + 1) and -2
    private val pool = ArrayBlockingQueue<ByteArray>(3)
    private val pending = ArrayBlockingQueue<ByteArray>(1)
    @Volatile private var lastOutputNanos = System.nanoTime()
    @Volatile private var closed = false
    @Volatile var failed = false
        private set
    private val process: Process
    init {
        repeat(3) { pool.add(ByteArray(width * height * 3)) }
        val rate = bitrateKbps.coerceIn(256, 20000)
        val encodeThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        process = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "rawvideo",
            "-pix_fmt", "rgb24", "-s", "${width}x$height", "-r", fps.toString(), "-i", "pipe:0",
            "-an", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-preset", "ultrafast",
            "-tune", "zerolatency", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-threads", encodeThreads.toString(),
            "-crf", "23", "-maxrate", "${rate}k", "-bufsize", "${rate / 4}k",
            "-x264-params", "aud=1:repeat-headers=1:keyint=$fps:min-keyint=$fps:scenecut=0:bframes=0:ref=1:sliced-threads=1",
            "-flush_packets", "1", "-f", "h264", "pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        thread(isDaemon = true, name = "vcb-h264-input") {
            try {
                process.outputStream.use { sink ->
                    while (!closed) {
                        val raw = pending.poll(250, TimeUnit.MILLISECONDS) ?: continue
                        try { sink.write(raw); sink.flush() } finally { pool.offer(raw) }
                    }
                }
            } catch (_: Exception) { if (!closed) failed = true }
            finally { close() }
        }
        thread(isDaemon = true, name = "vcb-h264-output") {
            var number = 0
            val parser = H264AccessUnits { data, key ->
                lastOutputNanos = System.nanoTime()
                if (!closed) output(this, number++, data, key)
            }
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16384)
                    while (!closed) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        parser.accept(buffer, 0, count)
                    }
                }
            } catch (_: Exception) { if (!closed) failed = true }
            finally { if (!closed) failed = true; close() }
        }
    }
    @Synchronized fun submit(rgb: ByteArray): Boolean {
        if (closed || failed || !process.isAlive) return false
        if (System.nanoTime() - lastOutputNanos > 3_000_000_000L) { failed = true; close(); return false }
        val target = pool.poll() ?: pending.poll() ?: return true // Encoder busy: skip a raw frame, never an encoded dependency.
        rgb.copyInto(target)
        if (!pending.offer(target)) { pending.poll()?.let { pool.offer(it) }; if (!pending.offer(target)) pool.offer(target) }
        return true
    }
    override fun close() { closed = true; process.destroyForcibly(); pending.clear(); pool.clear() }
    companion object { private val streams = AtomicInteger() }
}
