package su.plo.voice.discs.screen

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class VideoPlayer(
    val screen: Screen,
    val sourceUrl: String,
    val fps: Int = 15,
    val maxDistance: Int = 48,
    val ffmpegPath: String = "ffmpeg",
    val fallbackImageUrl: String? = null,
    val coroutineScope: CoroutineScope,
    val onCompletion: (() -> Unit)? = null
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var isPaused = false

    @Volatile
    private var isCurrentlyImage = false

    private val stopped = AtomicBoolean(false)
    private var playbackJob: Job? = null

    private val totalWidth = screen.totalWidthPixels
    private val totalHeight = screen.totalHeightPixels
    private val rows = screen.rows
    private val cols = screen.columns

    // Pre-allocated tile buffers for each item frame map (128x128 = 16,384 bytes)
    private val tileBuffers = Array(rows) { Array(cols) { ByteArray(16384) } }

    fun start(startPositionMs: Long = 0L) {
        if (stopped.get()) return
        isPlaying = true
        isPaused = false

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            runPlayback(startPositionMs)
        }
    }

    private suspend fun runPlayback(startPositionMs: Long) {
        if (sourceUrl.isBlank() && fallbackImageUrl.isNullOrBlank()) {
            clearScreen()
            return
        }

        val isImage = isImageUrl(sourceUrl)
        val hasFfmpeg = isFFmpegAvailable(ffmpegPath)

        logger.info("Screen '${screen.name}' playback check: sourceUrl='$sourceUrl', isImage=$isImage, hasFfmpeg=$hasFfmpeg, ffmpegPath='$ffmpegPath'")

        if (isImage || !hasFfmpeg) {
            isCurrentlyImage = true
            val imgUrl = if (isImage) sourceUrl else fallbackImageUrl
            if (imgUrl != null) {
                if (!hasFfmpeg && !isImage) {
                    if (!warnedNoFfmpeg.getAndSet(true)) {
                        logger.warn("FFmpeg is not installed or accessible at '$ffmpegPath'. Video streaming is unavailable; displaying track artwork/thumbnail instead. (To enable video, install ffmpeg or configure screen.ffmpeg_path in discs.toml)")
                    }
                } else if (isImage) {
                    logger.info("Displaying static image on screen '${screen.name}': $imgUrl")
                }
                renderImagePlayback(imgUrl)
                return
            } else if (!hasFfmpeg) {
                if (!warnedNoFfmpeg.getAndSet(true)) {
                    logger.warn("FFmpeg is not installed or accessible at '$ffmpegPath' and no image could be resolved for screen '${screen.name}'.")
                }
                clearScreen()
                return
            }
        }

        val isNetworkStream = sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")

        var httpConn: HttpURLConnection? = null
        var netStream: InputStream? = null

        if (isNetworkStream) {
            try {
                val (stream, conn) = openHttpStream(sourceUrl)
                netStream = stream
                httpConn = conn
            } catch (e: Exception) {
                logger.warn("Failed to open network stream for screen '${screen.name}': ${e.message}")
                val fallback = if (isImage) sourceUrl else fallbackImageUrl
                if (fallback != null) {
                    isCurrentlyImage = true
                    renderImagePlayback(fallback)
                    return
                } else {
                    clearScreen()
                    return
                }
            }
        }

        val frameSizeBytes = totalWidth * totalHeight * 3
        val rawFrameBuffer = ByteArray(frameSizeBytes)

        val command = mutableListOf<String>()
        command.add(ffmpegPath)
        command.add("-hide_banner")
        command.add("-nostats")
        command.add("-re")
        if (startPositionMs > 0) {
            val sec = startPositionMs / 1000.0
            command.add("-ss")
            command.add(String.format(Locale.US, "%.3f", sec))
        }
        if (isNetworkStream) {
            command.add("-i")
            command.add("pipe:0")
        } else {
            command.add("-i")
            command.add(sourceUrl)
        }
        command.add("-vf")
        command.add("scale=${totalWidth}:${totalHeight}:force_original_aspect_ratio=decrease,pad=${totalWidth}:${totalHeight}:(ow-iw)/2:(oh-ih)/2:black,fps=${fps}")
        command.add("-f")
        command.add("rawvideo")
        command.add("-pix_fmt")
        command.add("rgb24")
        command.add("pipe:1")

        logger.info("Starting FFmpeg video stream for screen '${screen.name}' at $fps FPS using $ffmpegPath")

        var proc: Process? = null
        val stderrLines = ConcurrentLinkedQueue<String>()
        var framesRendered = 0L
        var stdinJob: Job? = null

        try {
            proc = ProcessBuilder(command).start()
            process = proc

            if (netStream != null) {
                val stream = netStream
                val pipeOut = proc.outputStream
                stdinJob = coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val buf = ByteArray(65536)
                        while (!stopped.get() && proc.isAlive) {
                            val count = stream.read(buf)
                            if (count == -1) break
                            pipeOut.write(buf, 0, count)
                        }
                        pipeOut.flush()
                    } catch (_: Exception) {
                    } finally {
                        try { pipeOut.close() } catch (_: Exception) {}
                    }
                }
            }

            val stderrJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (stderrLines.size >= 30) stderrLines.poll()
                            stderrLines.offer(line)
                        }
                    }
                } catch (_: Exception) {}
            }

            val input = proc.inputStream

            while (!stopped.get() && coroutineScope.isActive) {
                if (isPaused) {
                    delay(100L)
                    continue
                }

                val ok = readFully(input, rawFrameBuffer)
                if (!ok) break // End of stream

                framesRendered++
                // Process frame and send packets
                val nearbyPlayers = getNearbyPlayers()
                if (nearbyPlayers.isNotEmpty()) {
                    sliceAndSend(rawFrameBuffer, nearbyPlayers)
                }
            }

            if (framesRendered == 0L && !stopped.get()) {
                withTimeoutOrNull(2000L) {
                    stderrJob.join()
                }
                val exitCode = try {
                    if (proc.isAlive) {
                        proc.waitFor(1, TimeUnit.SECONDS)
                    }
                    proc.exitValue()
                } catch (_: Exception) {
                    -1
                }
                val err = stderrLines.joinToString("\n").ifBlank { "(No stderr output)" }
                if (fallbackImageUrl != null) {
                    logger.warn("FFmpeg failed to produce video frames for screen '${screen.name}' (exit code: $exitCode). Stderr:\n$err\nFalling back to track artwork/thumbnail.")
                    isCurrentlyImage = true
                    renderImagePlayback(fallbackImageUrl)
                    return
                } else {
                    logger.warn("FFmpeg failed to produce video frames for screen '${screen.name}' (exit code: $exitCode). Stderr:\n$err")
                    clearScreen()
                    return
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException && (e.message?.contains("Cannot run program") == true || e.message?.contains("error: 2") == true || e.message?.contains("error: 13") == true)) {
                ffmpegCache[ffmpegPath] = false
                if (!warnedNoFfmpeg.getAndSet(true)) {
                    logger.warn("FFmpeg failed to execute: ${e.message}. Video streaming is disabled; displaying track artwork/thumbnail instead.")
                }
                val fallback = if (isImage) sourceUrl else fallbackImageUrl
                if (fallback != null) {
                    isCurrentlyImage = true
                    renderImagePlayback(fallback)
                    return
                } else {
                    clearScreen()
                    return
                }
            } else if (e !is CancellationException && !stopped.get()) {
                logger.error("Error during video playback for screen '${screen.name}': ${e.message}", e)
            }
        } finally {
            stdinJob?.cancel()
            try { netStream?.close() } catch (_: Exception) {}
            try { httpConn?.disconnect() } catch (_: Exception) {}
            val p = proc
            if (p != null) {
                if (p.isAlive) p.destroyForcibly()
                if (!stopped.get() && p.isAlive.not()) {
                    val exit = try { p.exitValue() } catch (_: Exception) { 0 }
                    if (exit != 0 && framesRendered > 0L && stderrLines.isNotEmpty()) {
                        logger.warn("FFmpeg exited with code $exit for screen '${screen.name}'. Log:\n${stderrLines.joinToString("\n")}")
                    }
                }
            }
            cleanup()
            onCompletion?.invoke()
        }
    }

    private suspend fun renderImagePlayback(imgUrl: String) {
        val rawFrameBuffer = ByteArray(totalWidth * totalHeight * 3)
        val loaded = withContext(Dispatchers.IO) {
            loadAndScaleImage(imgUrl, totalWidth, totalHeight, rawFrameBuffer)
        }

        if (!loaded) {
            clearScreen()
            return
        }

        val initialPlayers = getNearbyPlayers()
        if (initialPlayers.isNotEmpty()) {
            sliceAndSend(rawFrameBuffer, initialPlayers)
        }

        while (!stopped.get() && coroutineScope.isActive) {
            delay(1000L)
            if (isPaused) continue
            val nearbyPlayers = getNearbyPlayers()
            if (nearbyPlayers.isNotEmpty()) {
                sliceAndSend(rawFrameBuffer, nearbyPlayers)
            }
        }
    }

    private fun openHttpStream(urlStr: String): Pair<InputStream, HttpURLConnection> {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val url = URI(currentUrl).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            val ua = when {
                urlStr.contains("c=TVHTML5") -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
                urlStr.contains("c=ANDROID") -> "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            }
            conn.setRequestProperty("User-Agent", ua)
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                if (loc != null) {
                    currentUrl = loc
                    redirects++
                    conn.disconnect()
                    continue
                }
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP request failed with status $code: $urlStr")
            }
            return Pair(conn.inputStream, conn)
        }
        throw IOException("Too many redirects: $urlStr")
    }

    private fun loadAndScaleImage(
        urlStr: String,
        targetWidth: Int,
        targetHeight: Int,
        outBuffer: ByteArray
    ): Boolean {
        return try {
            val image: BufferedImage? = if (urlStr.startsWith("file://") || File(urlStr).exists()) {
                val path = if (urlStr.startsWith("file://")) urlStr.removePrefix("file://") else urlStr
                ImageIO.read(File(path))
            } else {
                val url = URI(urlStr).toURL()
                val conn = url.openConnection() as? HttpURLConnection
                conn?.apply {
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "image/jpeg,image/png,image/*;q=0.8")
                    connectTimeout = 5000
                    readTimeout = 10000
                }
                val stream = conn?.inputStream ?: url.openStream()
                stream.use { ImageIO.read(it) }
            }

            if (image == null) return false

            val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            try {
                g.color = Color.BLACK
                g.fillRect(0, 0, targetWidth, targetHeight)

                val scale = minOf(targetWidth.toDouble() / image.width, targetHeight.toDouble() / image.height)
                val sw = (image.width * scale).toInt()
                val sh = (image.height * scale).toInt()
                val ox = (targetWidth - sw) / 2
                val oy = (targetHeight - sh) / 2

                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(image, ox, oy, sw, sh, null)
            } finally {
                g.dispose()
            }

            for (y in 0 until targetHeight) {
                for (x in 0 until targetWidth) {
                    val rgb = scaled.getRGB(x, y)
                    val offset = (y * targetWidth + x) * 3
                    outBuffer[offset] = ((rgb shr 16) and 0xFF).toByte()
                    outBuffer[offset + 1] = ((rgb shr 8) and 0xFF).toByte()
                    outBuffer[offset + 2] = (rgb and 0xFF).toByte()
                }
            }
            true
        } catch (e: Exception) {
            logger.warn("Failed to load image from '$urlStr' for screen '${screen.name}': ${e.message}")
            false
        }
    }

    private fun getNearbyPlayers(): List<Player> {
        val maxDistSq = (maxDistance * maxDistance).toDouble()
        val result = ArrayList<Player>()
        for (player in Bukkit.getOnlinePlayers()) {
            val loc = player.location
            if (loc.world?.name != screen.worldName) continue
            val dx = loc.x - screen.centerX
            val dy = loc.y - screen.centerY
            val dz = loc.z - screen.centerZ
            if (dx * dx + dy * dy + dz * dz <= maxDistSq) {
                result.add(player)
            }
        }
        return result
    }

    private fun sliceAndSend(rawFrame: ByteArray, players: List<Player>) {
        val playerManager = PacketEvents.getAPI().playerManager

        for (r in 0 until rows) {
            val startY = r * 128
            for (c in 0 until cols) {
                val startX = c * 128
                val mapId = screen.mapIds.getOrNull(r)?.getOrNull(c) ?: continue
                val tileBuffer = tileBuffers[r][c]

                for (y in 0 until 128) {
                    val globalY = startY + y
                    val rowOffset = (globalY * totalWidth + startX) * 3
                    val tileRowOffset = y * 128
                    for (x in 0 until 128) {
                        val p = rowOffset + x * 3
                        val red = rawFrame[p].toInt() and 0xFF
                        val green = rawFrame[p + 1].toInt() and 0xFF
                        val blue = rawFrame[p + 2].toInt() and 0xFF
                        tileBuffer[tileRowOffset + x] = MapPaletteLookup.lookup(red, green, blue)
                    }
                }

                val packet = WrapperPlayServerMapData(
                    mapId,
                    0.toByte(),
                    false,
                    false,
                    emptyList(),
                    128,
                    128,
                    0,
                    0,
                    tileBuffer
                )

                for (player in players) {
                    playerManager.sendPacketSilently(player, packet)
                }
            }
        }
    }

    fun clearScreen() {
        val players = getNearbyPlayers()
        if (players.isEmpty()) return

        val playerManager = PacketEvents.getAPI().playerManager
        val blankBuffer = ByteArray(16384)
        MapPaletteLookup.fillBlank(blankBuffer)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val mapId = screen.mapIds.getOrNull(r)?.getOrNull(c) ?: continue
                val packet = WrapperPlayServerMapData(
                    mapId,
                    0.toByte(),
                    false,
                    false,
                    emptyList(),
                    128,
                    128,
                    0,
                    0,
                    blankBuffer
                )
                for (player in players) {
                    playerManager.sendPacketSilently(player, packet)
                }
            }
        }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("VideoPlayer")
        private val warnedNoFfmpeg = AtomicBoolean(false)
        private val ffmpegCache = ConcurrentHashMap<String, Boolean>()

        fun isFFmpegAvailable(ffmpegPath: String): Boolean {
            return ffmpegCache.computeIfAbsent(ffmpegPath) { path ->
                FFmpegResolver.testExecutable(path)
            }
        }

        fun clearFFmpegCache() {
            ffmpegCache.clear()
        }
    }

    fun seek(positionMs: Long) {
        if (stopped.get()) return
        if (isCurrentlyImage) return
        coroutineScope.launch(Dispatchers.IO) {
            process?.destroyForcibly()
            runPlayback(positionMs)
        }
    }

    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            isPlaying = false
            cleanup()
            clearScreen()
        }
    }

    private fun cleanup() {
        isPlaying = false
        playbackJob?.cancel()
        process?.let {
            it.destroyForcibly()
            process = null
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun isImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase(Locale.ROOT)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif") ||
                lower.contains("/vi/") || lower.contains("ytimg.com") ||
                lower.contains("googleusercontent.com") || lower.contains("ggpht.com")
    }
}
