package su.plo.voice.discs.screen

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.io.InputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class VideoPlayer(
    val screen: Screen,
    val sourceUrl: String,
    val fps: Int = 15,
    val maxDistance: Int = 48,
    val ffmpegPath: String = "ffmpeg",
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
        val frameSizeBytes = totalWidth * totalHeight * 3
        val rawFrameBuffer = ByteArray(frameSizeBytes)

        val isImage = isImageUrl(sourceUrl)
        val command = mutableListOf<String>()
        command.add(ffmpegPath)
        command.add("-re")
        if (isImage) {
            command.add("-loop")
            command.add("1")
        } else if (startPositionMs > 0) {
            val sec = startPositionMs / 1000.0
            command.add("-ss")
            command.add(String.format(Locale.US, "%.3f", sec))
        }
        command.add("-i")
        command.add(sourceUrl)
        val targetFps = if (isImage) 2 else fps
        command.add("-vf")
        command.add("scale=${totalWidth}:${totalHeight}:force_original_aspect_ratio=decrease,pad=${totalWidth}:${totalHeight}:(ow-iw)/2:(oh-ih)/2:black,fps=${targetFps}")
        command.add("-f")
        command.add("rawvideo")
        command.add("-pix_fmt")
        command.add("rgb24")
        command.add("pipe:1")

        var proc: Process? = null
        val stderrLines = java.util.concurrent.ConcurrentLinkedQueue<String>()

        try {
            proc = ProcessBuilder(command).start()
            process = proc

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (stderrLines.size >= 10) stderrLines.poll()
                            stderrLines.offer(line)
                        }
                    }
                } catch (_: Exception) {}
            }

            val input = proc.inputStream

            while (!stopped.get() && proc.isAlive && coroutineScope.isActive) {
                if (isPaused) {
                    delay(100L)
                    continue
                }

                val ok = readFully(input, rawFrameBuffer)
                if (!ok) break // End of stream

                // Process frame and send packets
                val nearbyPlayers = getNearbyPlayers()
                if (nearbyPlayers.isNotEmpty()) {
                    sliceAndSend(rawFrameBuffer, nearbyPlayers)
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && !stopped.get()) {
                logger.error("Error during video playback for screen '${screen.name}': ${e.message}", e)
            }
        } finally {
            val p = proc
            if (p != null) {
                if (p.isAlive) p.destroyForcibly()
                if (!stopped.get() && p.isAlive.not()) {
                    val exit = try { p.exitValue() } catch (_: Exception) { 0 }
                    if (exit != 0 && stderrLines.isNotEmpty()) {
                        logger.warn("FFmpeg exited with code $exit for screen '${screen.name}'. Log:\n${stderrLines.joinToString("\n")}")
                    }
                }
            }
            cleanup()
            onCompletion?.invoke()
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
        private val logger = su.plo.slib.api.logging.McLoggerFactory.createLogger("VideoPlayer")
    }

    fun seek(positionMs: Long) {
        if (stopped.get()) return
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
        val lower = url.lowercase(Locale.ROOT)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.contains("/vi/") || lower.contains("ytimg.com") ||
                lower.contains("googleusercontent.com") || lower.contains("ggpht.com")
    }
}

