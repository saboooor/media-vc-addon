package ca.saboor.discs.jellyfin

import ca.saboor.discs.screen.VideoSeekProxy
import ca.saboor.discs.screen.VideoPlayer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFormat
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor

/** Decode original codecs locally, retaining LavaPlayer's effects, volume and output encoding. */
internal class JellyfinLocalAudioTrack(
    info: AudioTrackInfo,
    private val source: AudioSourceManager,
    private val ffmpeg: () -> String,
    private val media: () -> Pair<String, String>,
) : BaseAudioTrack(info) {
    @Volatile var awaitingFirstFrame: Boolean = true
        private set

    override fun getSourceManager() = source
    override fun makeShallowClone(): AudioTrack = JellyfinLocalAudioTrack(info, source, ffmpeg, media)

    override fun process(executor: LocalAudioTrackExecutor) {
        val pipeline = AudioPipelineFactory.create(executor.processingContext, PcmFormat(2, 48000))
        var positionMs = 0L
        try {
            executor.executeProcessingLoop({
                awaitingFirstFrame = true
                val (url, authorization) = media()
                VideoSeekProxy(url, "vc-addon-discs", authorization).use { proxy ->
                    val command = mutableListOf(ffmpeg(), "-hide_banner", "-nostdin", "-v", "error")
                    command.addAll(VideoPlayer.proxiedInputOptions())
                    if (positionMs > 0) command.addAll(listOf("-ss", String.format(Locale.US, "%.3f", positionMs / 1000.0)))
                    command.addAll(listOf("-i", proxy.url, "-map", "0:a:0", "-vn", "-sn", "-dn",
                        "-ac", "2", "-ar", "48000", "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1"))
                    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    val chunks = ArrayBlockingQueue<Chunk>(2)
                    // Pipe reads cannot be interrupted by LavaPlayer's stop/seek signal. Keep
                    // them off the playback thread so it can always tear down FFmpeg promptly.
                    val reader = Thread({
                        try {
                            process.inputStream.use { input ->
                                while (true) {
                                    val bytes = input.readNBytes(3840)
                                    if (bytes.isEmpty()) break
                                    chunks.put(Chunk(bytes))
                                }
                            }
                            chunks.put(Chunk())
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (e: Exception) {
                            try { chunks.put(Chunk(error = e)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        }
                    }, "disc-jellyfin-audio").apply { isDaemon = true; start() }
                    try {
                        while (true) {
                            val timeout = if (awaitingFirstFrame) 30L else 15L
                            val chunk = chunks.poll(timeout, TimeUnit.SECONDS)
                                ?: throw IOException("Local Jellyfin audio decoder received no audio for ${timeout}s")
                            chunk.error?.let { throw IOException("Cannot read local Jellyfin audio", it) }
                            val bytes = chunk.bytes ?: break
                            if (bytes.size % 4 != 0) throw IOException("Incomplete local PCM audio sample")
                            val samples = ShortArray(bytes.size / 2)
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                            pipeline.process(samples, 0, samples.size)
                            awaitingFirstFrame = false
                        }
                        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
                            throw IOException("Local FFmpeg could not decode this Jellyfin audio track")
                        pipeline.flush()
                    } finally {
                        process.destroyForcibly()
                        reader.interrupt()
                        reader.join(1000)
                    }
                }
            }, { target ->
                positionMs = target
                pipeline.seekPerformed(target, target)
            })
        } finally { pipeline.close() }
    }
    private data class Chunk(val bytes: ByteArray? = null, val error: Exception? = null)
}
