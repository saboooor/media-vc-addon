package ca.saboor.discs.screen

import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Splits Annex B access units at AUD boundaries, independent of pipe read boundaries. */
internal class H264AccessUnits(private val emit: (ByteArray, Boolean) -> Unit) {
    private val bytes = ByteArrayOutputStream()
    private var zeros = 0
    private var header = false
    private var prefix = 0
    private var idr = false
    fun accept(value: Int) {
        val b = value and 255
        bytes.write(b)
        require(bytes.size() <= 4 * 1024 * 1024 + 5) { "Oversized H.264 access unit" }
        if (header) {
            if (b and 31 == 9) {
                val complete = bytes.toByteArray()
                val end = complete.size - prefix - 1
                if (end > 0) emit(complete.copyOf(end), idr)
                bytes.reset(); bytes.write(byteArrayOf(0, 0, 0, 1, b.toByte()))
                idr = false
            } else if (b and 31 == 5) idr = true
            header = false
        }
        if (b == 1 && zeros >= 2) { header = true; prefix = if (zeros >= 3) 4 else 3 }
        zeros = if (b == 0) zeros + 1 else 0
    }
    fun finish() { if (bytes.size() > 0) emit(bytes.toByteArray(), idr); bytes.reset() }
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
        process = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "rawvideo",
            "-pix_fmt", "rgb24", "-s", "${width}x$height", "-r", fps.toString(), "-i", "pipe:0",
            "-an", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-preset", "ultrafast",
            "-tune", "zerolatency", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-threads", "1",
            "-crf", "23", "-maxrate", "${rate}k", "-bufsize", "${rate / 4}k",
            "-x264-params", "aud=1:repeat-headers=1:keyint=$fps:min-keyint=$fps:scenecut=0:bframes=0:ref=1",
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
                        for (i in 0 until count) parser.accept(buffer[i].toInt())
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
