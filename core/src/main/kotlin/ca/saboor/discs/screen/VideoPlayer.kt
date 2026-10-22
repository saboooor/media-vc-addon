package ca.saboor.discs.screen

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
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
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player

class VideoPlayer(
    val screen: Screen,
    val sourceUrl: String,
    fps: Int = 15,
    val maxDistance: Int = 48,
    val ffmpegPath: String = "ffmpeg",
    val fallbackImageUrl: String? = null,
    val coroutineScope: CoroutineScope,
    val onCompletion: (() -> Unit)? = null,
    private val sourceAuthorization: String? = null,
    ditherStrength: Int = 12,
    private val companionRgb: Boolean = true,
    private val companionQuality: Int = 85,
    private val companionResolution: String = "map",
    val playbackPositionMs: (() -> Long?)? = null
) {
    @Volatile var fps: Int = fps.coerceIn(1, 30)
        private set

    fun updateFrameRate(value: Int) {
        val next = value.coerceIn(1, 30)
        if (fps == next) return
        fps = next
        if (isPlaying && !stopped.get()) {
            restartPlayback(playbackPositionMs?.invoke() ?: displayedPositionMs)
        }
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var isPaused = false
        set(value) {
            if (field != value && isPlaying) {
                logger.info("Video on screen '${screen.name}' ${if (value) "paused" else "resumed"}")
            }
            field = value
        }

    @Volatile
    private var isCurrentlyImage = false

    private val stopped = AtomicBoolean(false)
    @Volatile private var playbackJob: Job? = null
    private val playbackMutex = Mutex()

    private val totalWidth = screen.totalWidthPixels
    private val totalHeight = screen.totalHeightPixels
    private val videoSize = CompanionResolution.size(totalWidth, totalHeight, if (companionRgb) companionResolution else "map")
    private val mapScaler = RgbFrameScaler()
    private val fallbackRgbEncoder = CompanionFrameEncoder()
    private val rows = screen.rows
    private val cols = screen.columns

    // Pre-allocated tile buffers for each item frame map (128x128 = 16,384 bytes)
    private val tileBuffers = Array(rows) { Array(cols) { ByteArray(16384) } }

    private val dither = if (ditherStrength > 0) OrderedMapDither(ditherStrength) else null
    private val rgbEncoder = CompanionFrameEncoder()
    private val tileDeltas = Array(rows) { Array(cols) { MapTileDelta() } }
    private val rgbTiles = Array(rows) { Array(cols) { RgbTileCache() } }
    private val companionRecipients = FrameRecipients<Player>()
    private var audienceChecked = Long.MIN_VALUE
    private var cachedAudience: List<Player> = emptyList()
    private var previousViewers: Map<UUID, Player> = emptyMap()
    private val mapViewers = ScreenMapViewers(screen.mapIds.flatten())

    internal fun isDisplayingMap(viewerId: UUID, mapId: Int): Boolean =
        isPlaying && mapViewers.owns(viewerId, mapId)

    @Volatile var displayedPositionMs: Long = 0L
        private set

    fun start(startPositionMs: Long = 0L) {
        if (stopped.get()) return
        isPaused = false
        restartPlayback(startPositionMs)
    }

    private fun restartPlayback(positionMs: Long) {
        displayedPositionMs = positionMs.coerceAtLeast(0)
        coroutineScope.launch(Dispatchers.IO) {
            playbackMutex.withLock {
                val previous = playbackJob
                playbackJob = null
                previous?.cancel()
                process?.destroyForcibly() // Unblock a pending raw-frame read before joining.
                previous?.join()
                if (stopped.get()) return@withLock
                isPlaying = true
                isCurrentlyImage = false
                val next = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        var resumePositionMs = positionMs.coerceAtLeast(0)
                        var recoveries = 0
                        while (currentCoroutineContext().isActive && !stopped.get()) {
                            try {
                                runPlayback(resumePositionMs)
                                break
                            } catch (e: StalledPlayback) {
                                if (++recoveries > 3) {
                                    logger.error("Video for screen '${screen.name}' stalled repeatedly; stopping playback")
                                    break
                                }
                                resumePositionMs = playbackPositionMs?.invoke()?.coerceAtLeast(0) ?: e.positionMs
                                logger.warn("Restarting stalled video for screen '${screen.name}' at ${resumePositionMs}ms (attempt $recoveries/3)")
                            }
                        }
                    } finally {
                        if (playbackJob === currentCoroutineContext()[Job]) {
                            playbackJob = null
                            isPlaying = false
                            if (!stopped.get()) onCompletion?.invoke()
                        }
                    }
                }
                playbackJob = next
                if (stopped.get()) next.cancel() else next.start()
            }
        }
    }

    private suspend fun runPlayback(startPositionMs: Long) {
        if (sourceUrl.isBlank() && fallbackImageUrl.isNullOrBlank()) {
            clearScreen()
            return
        }

        val isImage = isImageUrl(sourceUrl)
        val hasFfmpeg = isFFmpegAvailable(ffmpegPath)

        logger.info("Screen '${screen.name}' playback check: source='${describeSource(sourceUrl)}', isImage=$isImage, hasFfmpeg=$hasFfmpeg, ffmpegPath='$ffmpegPath'")

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

        // stdin cannot seek; a loopback range proxy keeps HTTPS handling in Java on seeks.
        val pipeNetworkStream = isNetworkStream && startPositionMs <= 0 && sourceAuthorization == null
        var httpConn: HttpURLConnection? = null
        var netStream: InputStream? = null

        if (pipeNetworkStream) {
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

        val seekProxy = if (isNetworkStream && !pipeNetworkStream) VideoSeekProxy(sourceUrl, when {
            sourceUrl.contains("c=TVHTML5") -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
            sourceUrl.contains("c=ANDROID") -> "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
            else -> "Mozilla/5.0"
        }, sourceAuthorization) else null

        val outputSizeReady = CompletableDeferred<CompanionResolution.Size>()
        val colorInfoReady = CompletableDeferred<VideoColorInfo>()
        var toneMapper: VideoToneMapper? = null

        val command = mutableListOf<String>()
        command.add(ffmpegPath)
        command.add("-hide_banner")
        command.add("-nostats")
        command.addAll(listOf("-progress", "pipe:2"))
        if (startPositionMs > 0) {
            val sec = startPositionMs / 1000.0
            command.add("-ss")
            command.add(String.format(Locale.US, "%.3f", sec))
        }
        if (pipeNetworkStream) {
            command.add("-i")
            command.add("pipe:0")
        } else {
            if (seekProxy != null) {
                command.addAll(proxiedInputOptions())
            }
            command.add("-i")
            command.add(seekProxy?.url ?: sourceUrl)
        }
        command.addAll(listOf("-map", "0:v:0", "-an", "-sn", "-dn"))
        command.add("-vf")
        // Drop surplus source frames before scaling and padding.
        val audioClock = playbackPositionMs
        val frameTimes = Channel<Long>(Channel.UNLIMITED)
        val filter = videoFilter(fps, videoSize.width, videoSize.height, videoSize.width != totalWidth || videoSize.height != totalHeight)
        // With linked audio, pace by decoded frame timestamps rather than a second wall clock.
        command.add((if (audioClock != null) filter.removeSuffix(",realtime") else filter) + ",showinfo=checksum=0")
        // Raw RGB needs no frame-threaded encoder queue; flush each frame to the renderer.
        command.addAll(listOf("-threads:v", "1", "-flush_packets", "1"))
        command.add("-f")
        command.add("rawvideo")
        command.add("-pix_fmt")
        command.add("rgb48le")
        command.add("pipe:1")

        logger.info("Starting FFmpeg video stream for screen '${screen.name}' at up to $fps FPS using $ffmpegPath")

        var proc: Process? = null
        val stderrLines = ConcurrentLinkedQueue<String>()
        var framesRendered = 0L
        var stdinJob: Job? = null
        var watchdogJob: Job? = null
        val readMonitor = VideoReadMonitor(TimeUnit.SECONDS.toNanos(15))
        val renderMonitor = VideoReadMonitor(TimeUnit.SECONDS.toNanos(15))
        val renderWarning = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        val outputPositionUs = AtomicLong(0L)

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
                var inputColor: VideoColorInfo? = null
                var outputStarted = false
                try {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!outputSizeReady.isCompleted) CompanionResolution.parseOutputSize(line)?.let { outputSizeReady.complete(it) }
                            VideoAudioSync.timestamp(line)?.let { frameTimes.trySend(it) }
                            if (!colorInfoReady.isCompleted) {
                                if (line.startsWith("Output #0,")) outputStarted = true
                                val streamColor = VideoColorInfo.fromStreamLine(line)
                                if (outputStarted && streamColor != null) {
                                    // Some decoders expose transfer metadata only after decoding
                                    // the first frame. RGB48 retains it in the output stream header.
                                    colorInfoReady.complete(if (streamColor.transfer != VideoTransfer.SDR)
                                        streamColor else inputColor ?: streamColor)
                                } else if (!outputStarted && inputColor == null) inputColor = streamColor
                            }
                            if (line.startsWith("out_time_us=")) {
                                line.substringAfter('=').toLongOrNull()?.let { outputPositionUs.set(it) }
                            }
                            // Progress is machine-readable and must not displace decoder diagnostics.
                            if (!progressLine.matches(line) && !line.contains("showinfo")) {
                                if (stderrLines.size >= 30) stderrLines.poll()
                                stderrLines.offer(line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    outputSizeReady.completeExceptionally(e)
                } finally {
                    // With no video output, retain the normal zero-frame artwork fallback.
                    outputSizeReady.complete(videoSize)
                    frameTimes.close()
                    colorInfoReady.complete(inputColor ?: VideoColorInfo())
                }
            }

            val input = proc.inputStream
            watchdogJob = coroutineScope.launch(Dispatchers.IO) {
                while (isActive && proc.isAlive && !stopped.get()) {
                    delay(1000L)
                    if (renderMonitor.isStalled(System.nanoTime(), false) &&
                        renderWarning.compareAndSet(false, true)) {
                        logger.warn("Video frame rendering or packet delivery has blocked for 15 seconds " +
                            "on screen '${screen.name}'; frame delivery has not completed")
                    }
                    if (readMonitor.isStalled(System.nanoTime(), isPaused) &&
                        stalled.compareAndSet(false, true)) {
                        val details = stderrLines.joinToString("\n")
                            .replace(sourceUrl, describeSource(sourceUrl))
                        logger.warn("No video frame received for 15 seconds on screen '${screen.name}' " +
                            "(FFmpeg alive=${proc.isAlive}, position=${startPositionMs + outputPositionUs.get() / 1000}ms). " +
                            "Restarting decoder. Recent FFmpeg output:\n$details")
                        proc.destroyForcibly() // Coroutine cancellation alone cannot unblock InputStream.read.
                        break
                    }
                }
            }

            val outputSize = outputSizeReady.await()
            val frameSizeBytes = outputSize.width * outputSize.height * 3
            val rawFrameBuffer = ByteArray(frameSizeBytes)
            val decodedFrameBuffer = ByteArray(frameSizeBytes * 2)

            while (!stopped.get() && currentCoroutineContext().isActive) {
                if (isPaused) {
                    delay(100L)
                    continue
                }

                readMonitor.beginRead(System.nanoTime())
                val ok = try {
                    readFully(input, decodedFrameBuffer)
                } finally {
                    readMonitor.endRead()
                }
                if (stalled.get()) throw StalledPlayback(startPositionMs + outputPositionUs.get() / 1000)
                if (!ok) break // End of stream

                framesRendered++
                val relativeMs = frameTimes.receiveCatching().getOrNull()
                    ?: throw IOException("Missing video frame timestamp")
                val videoMs = startPositionMs + relativeMs
                if (audioClock != null) {
                    while (!stopped.get() && currentCoroutineContext().isActive) {
                        if (isPaused) { delay(20L); continue }
                        val audioMs = audioClock()
                        if (audioMs == null) { delay(20L); continue }
                        val waitMs = VideoAudioSync.waitMillis(videoMs, audioMs)
                        if (waitMs == 0L) break
                        delay(waitMs)
                    }
                    currentCoroutineContext().ensureActive()
                    if (stopped.get()) break
                    if (VideoAudioSync.stale(videoMs, audioClock() ?: 0L)) continue
                }
                displayedPositionMs = videoMs
                // Process frame and send packets
                renderWarning.set(false)
                renderMonitor.beginRead(System.nanoTime())
                try {
                    val nearbyPlayers = getNearbyPlayers()
                    if (nearbyPlayers.isEmpty()) {
                        // Drain decoded frames to preserve timing, but do no color/render work.
                        sliceAndSend(rawFrameBuffer, nearbyPlayers, outputSize.width, outputSize.height)
                        continue
                    }
                    val mapper = toneMapper ?: VideoToneMapper(colorInfoReady.await().also { color ->
                        if (color.transfer != VideoTransfer.SDR) {
                            logger.info("Screen '${screen.name}': tone mapping ${color.transfer} HDR to SDR")
                        }
                    }).also { toneMapper = it }
                    mapper.convert(decodedFrameBuffer, rawFrameBuffer)
                    sliceAndSend(rawFrameBuffer, nearbyPlayers, outputSize.width, outputSize.height)
                } finally {
                    renderMonitor.endRead()
                }
            }

            currentCoroutineContext().ensureActive()
            if (!stopped.get() && framesRendered > 0L) {
                logger.info("Video stream ended on screen '${screen.name}' after $framesRendered frames " +
                    "at ${startPositionMs + outputPositionUs.get() / 1000}ms")
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
            if (e is CancellationException) throw e
            if (!currentCoroutineContext().isActive || stopped.get()) return
            if (stalled.get()) throw StalledPlayback(startPositionMs + outputPositionUs.get() / 1000)
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
            watchdogJob?.cancel()
            stdinJob?.cancel()
            val p = proc
            if (p != null) {
                // Inspect the exit BEFORE cleanup kills a still-running process. A seek cancels
                // this job before terminating FFmpeg and must not look like an external failure.
                val exit = if (!p.isAlive) runCatching { p.exitValue() }.getOrNull() else null
                if (p.isAlive) p.destroyForcibly()
                if (!stalled.get() && shouldReportExit(exit, !currentCoroutineContext().isActive, stopped.get()) &&
                    framesRendered > 0L && stderrLines.isNotEmpty()) {
                    val details = stderrLines.joinToString("\n").replace(sourceUrl, describeSource(sourceUrl))
                    logger.warn("FFmpeg exited with code $exit for screen '${screen.name}'. Log:\n$details")
                }
            }
            seekProxy?.close()
            try { netStream?.close() } catch (_: Exception) {}
            try { httpConn?.disconnect() } catch (_: Exception) {}
            if (process === proc) process = null
        }
    }

    private class StalledPlayback(val positionMs: Long) : IOException("Video frame read stalled")

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
        sliceAndSend(rawFrameBuffer, initialPlayers)

        while (!stopped.get() && currentCoroutineContext().isActive) {
            delay(1000L)
            if (isPaused) continue
            val nearbyPlayers = getNearbyPlayers()
            sliceAndSend(rawFrameBuffer, nearbyPlayers)
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
                throw IOException("HTTP request failed with status $code: ${describeSource(urlStr)}")
            }
            return Pair(conn.inputStream, conn)
        }
        throw IOException("Too many redirects: ${describeSource(urlStr)}")
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

    @Synchronized
    private fun getNearbyPlayers(): List<Player> {
        val now = System.nanoTime()
        if (audienceChecked == Long.MIN_VALUE || now - audienceChecked >= 250_000_000L) {
            cachedAudience = ca.saboor.discs.utils.PlayerAudience.nearby(
                screen.worldName, screen.centerX, screen.centerY, screen.centerZ, maxDistance.toDouble())
            audienceChecked = now
        }
        return cachedAudience
    }

    @Synchronized
    private fun sliceAndSend(sourceFrame: ByteArray, players: List<Player>, width: Int = totalWidth, height: Int = totalHeight) {
        if (stopped.get()) return
        mapViewers.update(players.map { it.uniqueId })
        if (players.isEmpty()) {
            previousViewers = emptyMap()
            companionRecipients.clear()
            return
        }
        val rawFrame = mapScaler.scale(sourceFrame, width, height, totalWidth, totalHeight)
        val newViewers = players.filter { previousViewers[it.uniqueId] !== it }
        val existingViewers = players.filter { previousViewers[it.uniqueId] === it }
        val playerManager = PacketEvents.getAPI().playerManager

        if (companionRgb && players.any(CompanionScreens::capable)) {
            try {
                val capable = players.filter(CompanionScreens::capable)
                val jpeg = try { rgbEncoder.encode(sourceFrame, width, height, companionQuality) }
                    catch (_: IllegalArgumentException) { fallbackRgbEncoder.encode(rawFrame, totalWidth, totalHeight, companionQuality) }
                val recipients = companionRecipients.select(jpeg, capable, System.nanoTime())
                if (recipients.isNotEmpty()) CompanionScreens.frame(screen, jpeg, recipients)
            } catch (e: Exception) {
                logger.debug("RGB screen encoding failed; palette fallback remains available", e)
            }
        }

        for (r in 0 until rows) {
            val startY = r * 128
            for (c in 0 until cols) {
                val startX = c * 128
                val mapId = screen.mapIds.getOrNull(r)?.getOrNull(c) ?: continue
                val tileBuffer = tileBuffers[r][c]

                val rgbChanged = rgbTiles[r][c].update(rawFrame, totalWidth, startX, startY)
                if (rgbChanged) for (y in 0 until 128) {
                    val globalY = startY + y
                    val rowOffset = (globalY * totalWidth + startX) * 3
                    val tileRowOffset = y * 128
                    for (x in 0 until 128) {
                        val p = rowOffset + x * 3
                        val red = rawFrame[p].toInt() and 0xFF
                        val green = rawFrame[p + 1].toInt() and 0xFF
                        val blue = rawFrame[p + 2].toInt() and 0xFF
                        tileBuffer[tileRowOffset + x] = dither?.lookup(red, green, blue, startX + x, globalY)
                            ?: MapPaletteLookup.lookup(red, green, blue)
                    }
                }

                val changed = if (rgbChanged) tileDeltas[r][c].update(tileBuffer) else null
                fun send(region: MapTileRegion, viewers: List<Player>) {
                    val packet = WrapperPlayServerMapData(
                        mapId, 0.toByte(), false, false, emptyList(),
                        region.width, region.height, region.x, region.y, region.colors
                    )
                    for (player in viewers) playerManager.sendPacketSilently(player, packet)
                }
                // New and returning viewers need a complete tile, even if nothing changed.
                if (newViewers.isNotEmpty()) {
                    val full = if (changed?.width == 128 && changed.height == 128) changed
                        else MapTileRegion(0, 0, 128, 128, tileBuffer.copyOf())
                    send(full, newViewers)
                }
                if (changed != null && existingViewers.isNotEmpty()) send(changed, existingViewers)
            }
        }
        previousViewers = players.associateBy { it.uniqueId }
    }

    @Synchronized
    fun clearScreen() {
        companionRecipients.clear()
        audienceChecked = Long.MIN_VALUE
        mapViewers.update(emptyList())
        // Also release overrides held by clients that have already left viewing range.
        for (row in screen.mapIds) for (mapId in row) CompanionScreens.clear(mapId)
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
        internal fun proxiedInputOptions(): List<String> = listOf(
            // Buffer compressed packets independently of timestamp-paced frame delivery.
            "-thread_queue_size", "256",
            // Skip nearby MKV data by reading ahead rather than opening another HTTP range.
            "-short_seek_size", "1048576",
            "-reconnect", "1",
            "-reconnect_on_network_error", "1",
            "-reconnect_delay_max", "5"
        )

        internal fun videoFilter(fps: Int, width: Int, height: Int, preventUpscale: Boolean = false): String {
            val scale = if (preventUpscale) "scale='min(iw,$width)':'min(ih,$height)':force_original_aspect_ratio=decrease,"
                else "scale=$width:$height:force_original_aspect_ratio=decrease,"
            val base = "fps='if(gt(source_fps,0),min(source_fps,$fps),$fps)'," +
                scale +
                (if (preventUpscale) "pad=w='max(iw,ceil(ih*$width/$height))':h='max(ih,ceil(iw*$height/$width))':x=(ow-iw)/2:y=(oh-ih)/2:color=black"
                else "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:black")
            return "$base,realtime"
        }

        internal fun shouldReportExit(exit: Int?, cancelled: Boolean, stopped: Boolean): Boolean =
            exit != null && exit != 0 && !cancelled && !stopped

        internal fun describeSource(url: String): String = runCatching {
            val uri = URI(url)
            if (uri.host != null) "${uri.scheme}://${uri.host}${uri.path.orEmpty()}" else File(url).name
        }.getOrDefault("video source")

        private val progressLine = Regex("[a-zA-Z0-9_]+=.*")
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
        if (stopped.get() || (isCurrentlyImage && isImageUrl(sourceUrl))) return
        restartPlayback(positionMs)
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

