package ca.saboor.discs.audio

import ca.saboor.discs.PlaybackControls
import ca.saboor.discs.screen.VideoSourceResolver
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataOutput
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.thread
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.*
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import su.plo.voice.lavaplayer.libs.org.apache.http.client.config.RequestConfig
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.HttpClientBuilder
import su.plo.voice.lavaplayer.libs.org.apache.http.message.BasicHeader

/**
 * SoundCloud audio source that tries LavaPlayer's native extractor first and falls back
 * to yt-dlp when native extraction fails (e.g. client_id rotation or short-link redirects).
 */
internal class SoundCloudFallbackAudioSourceManager(
    private val native: AudioSourceManager = SoundCloudAudioSourceManager.createDefault(),
    private val ytdlpExecutable: () -> String = { "yt-dlp" },
    private val ytdlpConfig: () -> String = { "" },
    private val timeoutSeconds: Long = 25L,
) : AudioSourceManager, HttpConfigurable {

    override fun getSourceName(): String = "soundcloud"

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier ?: return null
        if (!accepts(identifier)) return null

        val nativeFailure: Exception? = try {
            val loaded = native.loadItem(manager, reference)
            if (loaded != null) return loaded
            null
        } catch (e: Exception) {
            e
        }

        return try {
            loadWithYtDlp(manager, identifier)
        } catch (fallbackError: Exception) {
            if (nativeFailure != null) throw nativeFailure
            throw fallbackError
        }
    }

    private fun loadWithYtDlp(manager: AudioPlayerManager, identifier: String): AudioItem {
        val isSearch = identifier.startsWith("scsearch:", ignoreCase = true)
        val target = if (isSearch) {
            val query = identifier.substringAfter(':').trim()
            require(query.isNotEmpty()) { "SoundCloud search query cannot be empty" }
            "scsearch10:$query"
        } else {
            identifier.trim()
        }
        val isPlaylist = isSearch || isPlaylistUrl(target)
        val args = if (isPlaylist) {
            listOf("--yes-playlist", "--flat-playlist", "--playlist-end", "500", "--", target)
        } else {
            listOf("--no-playlist", "-f", AUDIO_FORMAT, "--", target)
        }
        val json = runYtDlp(args)
        val entries = json.getAsJsonArray("entries")
        if (entries != null) {
            val tracks = entries.mapNotNull { entry ->
                runCatching { parseMedia(entry.asJsonObject, flat = true) }.getOrNull()
            }.map { SoundCloudYtDlpTrack(it, manager) }
            val title = text(json, "title", "SoundCloud")
            return BasicAudioPlaylist(title, tracks, tracks.firstOrNull(), isSearch)
        }
        val media = parseMedia(json, flat = false)
        return SoundCloudYtDlpTrack(media, manager)
    }

    private inner class SoundCloudYtDlpTrack(
        private val media: Media,
        private val manager: AudioPlayerManager,
    ) : DelegatedAudioTrack(
        AudioTrackInfo(media.title, media.author, media.durationMs, media.webpageUrl, false, media.webpageUrl, media.artworkUrl, null)
    ) {
        override fun getSourceManager(): AudioSourceManager = this@SoundCloudFallbackAudioSourceManager
        override fun isSeekable(): Boolean = media.durationMs > 0
        override fun getDuration(): Long = media.durationMs

        override fun process(executor: LocalAudioTrackExecutor) {
            val resolved = if (media.url != null) media else {
                val json = runYtDlp(listOf("--no-playlist", "-f", AUDIO_FORMAT, "--", media.webpageUrl))
                parseMedia(json, flat = false)
            }
            val streamUrl = resolved.url ?: error("yt-dlp did not return a playable SoundCloud stream")
            getUserData(PlaybackControls::class.java)?.let {
                it.sourceAvailableFormats = resolved.format
                it.sourceFormatDescription = "${resolved.format} · SoundCloud (yt-dlp)"
            }
            val http = HttpAudioSourceManager()
            try {
                if (resolved.headers.isNotEmpty()) {
                    http.configureBuilder { builder ->
                        builder.setDefaultHeaders(resolved.headers.map { BasicHeader(it.key, it.value) })
                    }
                }
                val delegate = http.loadItem(manager, AudioReference(streamUrl, media.title)) as? InternalAudioTrack
                    ?: error("yt-dlp returned an unsupported SoundCloud audio format")
                processDelegate(delegate, executor)
            } finally {
                http.shutdown()
            }
        }

        override fun makeShallowClone(): AudioTrack = SoundCloudYtDlpTrack(media, manager)
    }

    private fun runYtDlp(arguments: List<String>): JsonObject {
        val executable = ytdlpExecutable().ifBlank { "yt-dlp" }
        val configFile = ytdlpConfig()
        val command = mutableListOf(executable, "--ignore-config")
        if (configFile.isNotBlank()) command.addAll(listOf("--config-locations", configFile))
        command.addAll(
            listOf(
                "--dump-single-json",
                "--skip-download",
                "--no-progress",
                "--no-warnings",
                "--socket-timeout",
                "10",
                "--retries",
                "1",
                "--extractor-retries",
                "1",
            )
        )
        command.addAll(arguments)
        val proc = ProcessBuilder(command).start()
        try {
            proc.outputStream.close()
            val stdout = Capture(proc.inputStream, 8 * 1024 * 1024, proc)
            val outThread = thread(isDaemon = true, name = "disc-sc-ytdlp-out") { stdout.read() }
            val errThread = thread(isDaemon = true, name = "disc-sc-ytdlp-err") {
                runCatching { proc.errorStream.use { it.readAllBytes() } }
            }
            check(proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "SoundCloud extraction timed out after ${timeoutSeconds}s" }
            outThread.join(1000)
            errThread.join(1000)
            check(!stdout.overflow) { "SoundCloud extractor response exceeded the size limit" }
            check(proc.exitValue() == 0) { "SoundCloud extraction failed with exit code ${proc.exitValue()}" }
            return JsonParser.parseString(stdout.text()).asJsonObject
        } finally {
            proc.descendants().forEach { it.destroyForcibly() }
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    private class Capture(private val input: InputStream, private val limit: Int, private val process: Process) {
        private val bytes = ByteArrayOutputStream()
        @Volatile var overflow = false
        fun read() {
            runCatching {
                input.use {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        val retained = minOf(count, limit - bytes.size())
                        if (retained > 0) bytes.write(buffer, 0, retained)
                        if (retained < count) {
                            overflow = true
                            process.destroyForcibly()
                            break
                        }
                    }
                }
            }
        }
        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    override fun configureRequests(configurator: java.util.function.Function<RequestConfig, RequestConfig>) {
        (native as? HttpConfigurable)?.configureRequests(configurator)
    }

    override fun configureBuilder(configurator: Consumer<HttpClientBuilder>) {
        (native as? HttpConfigurable)?.configureBuilder(configurator)
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean = native.isTrackEncodable(track)
    override fun encodeTrack(track: AudioTrack, output: DataOutput) = native.encodeTrack(track, output)
    override fun decodeTrack(info: AudioTrackInfo, input: DataInput): AudioTrack? = native.decodeTrack(info, input)
    override fun shutdown() = native.shutdown()

    data class Media(
        val webpageUrl: String,
        val title: String,
        val author: String,
        val durationMs: Long,
        val artworkUrl: String?,
        val url: String?,
        val headers: Map<String, String>,
        val format: String,
    )

    companion object {
        private const val AUDIO_FORMAT = "bestaudio[protocol!=m3u8_native][protocol!=m3u8]/bestaudio/best"

        internal fun accepts(identifier: String): Boolean {
            val trimmed = identifier.trim()
            if (trimmed.startsWith("scsearch:", ignoreCase = true)) return true
            return VideoSourceResolver.isSoundCloudUrl(trimmed)
        }

        internal fun isPlaylistUrl(url: String): Boolean {
            val path = runCatching { URI(url).path?.lowercase() }.getOrNull() ?: return false
            return path.contains("/sets/") || path.endsWith("/likes") || path.endsWith("/tracks")
        }

        private fun text(json: JsonObject, key: String, fallback: String = ""): String =
            json.get(key)?.takeUnless { it.isJsonNull }?.asString ?: fallback

        internal fun parseMedia(json: JsonObject, flat: Boolean = false): Media {
            val webpageUrl = text(json, "webpage_url", text(json, "url")).takeIf { it.isNotBlank() }
                ?: error("Missing SoundCloud URL")
            val streamUrl = if (flat) null else text(json, "url").takeIf { it.isNotBlank() }
            streamUrl?.let { require(URI(it).scheme in listOf("http", "https") && URI(it).host != null) }
            val headers = if (flat) emptyMap() else json.getAsJsonObject("http_headers")?.entrySet()
                ?.associate { it.key to it.value.asString }.orEmpty()
            val artwork = text(json, "thumbnail").takeIf { it.isNotBlank() }
            return Media(
                webpageUrl = webpageUrl,
                title = text(json, "title", "SoundCloud Track"),
                author = text(json, "uploader", text(json, "channel", "SoundCloud")),
                durationMs = ((json.get("duration")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0) * 1000).toLong(),
                artworkUrl = artwork,
                url = streamUrl,
                headers = headers,
                format = text(json, "format", "SoundCloud audio"),
            )
        }
    }
}
