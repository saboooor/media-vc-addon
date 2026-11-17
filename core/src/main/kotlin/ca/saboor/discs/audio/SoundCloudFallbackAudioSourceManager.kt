package ca.saboor.discs.audio

import ca.saboor.discs.PlaybackControls
import ca.saboor.discs.screen.VideoSourceResolver
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFormat
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudFormatHandler
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudFormatHandler
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.*
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import su.plo.voice.lavaplayer.libs.org.apache.http.client.config.RequestConfig
import su.plo.voice.lavaplayer.libs.org.apache.http.client.methods.HttpGet
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.HttpClientBuilder
import su.plo.voice.lavaplayer.libs.org.apache.http.message.BasicHeader

/**
 * SoundCloud audio source that tries LavaPlayer's native extractor first (including FFmpeg
 * decoding for HLS/AAC formats unsupported by LavaPlayer's built-in SoundCloud decoder) and
 * falls back to yt-dlp when native extraction fails.
 */
internal class SoundCloudFallbackAudioSourceManager(
    private val native: AudioSourceManager = createNativeSourceManager(),
    private val ytdlpExecutable: () -> String = { "yt-dlp" },
    private val ytdlpConfig: () -> String = { "" },
    private val ffmpegExecutable: () -> String = { "ffmpeg" },
    private val timeoutSeconds: Long = 25L,
) : AudioSourceManager, HttpConfigurable {

    private val logger = LoggerFactory.getLogger("SoundCloudFallback")

    override fun getSourceName(): String = "soundcloud"

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier ?: return null
        if (!accepts(identifier)) return null

        val nativeFailure: Exception? = try {
            val loaded = native.loadItem(manager, reference)
            if (loaded != null) return wrapNativeItem(loaded)
            null
        } catch (e: Exception) {
            e
        }

        return try {
            loadWithYtDlp(manager, identifier)
        } catch (fallbackError: Exception) {
            logger.warn("SoundCloud yt-dlp fallback failed for '$identifier': ${fallbackError.message}")
            if (nativeFailure != null) {
                throw IllegalStateException(
                    "SoundCloud lookup failed (native: ${nativeFailure.message}; yt-dlp: ${fallbackError.message})",
                    fallbackError,
                ).apply { addSuppressed(nativeFailure) }
            }
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
            val delegate = if (!streamUrl.contains(".m3u8", ignoreCase = true)) {
                loadHttpDelegate(streamUrl, resolved.headers)
            } else {
                null
            }
            if (delegate != null) {
                try {
                    processDelegate(delegate, executor)
                    return
                } finally {
                    (delegate.sourceManager as? HttpAudioSourceManager)?.shutdown()
                }
            }
            processFfmpeg(streamUrl, resolved.headers, executor)
        }

        private fun loadHttpDelegate(streamUrl: String, headers: Map<String, String>): InternalAudioTrack? {
            val http = HttpAudioSourceManager()
            return try {
                if (headers.isNotEmpty()) {
                    http.configureBuilder { builder ->
                        builder.setDefaultHeaders(headers.map { BasicHeader(it.key, it.value) })
                    }
                }
                val track = http.loadItem(manager, AudioReference(streamUrl, media.title)) as? InternalAudioTrack
                if (track == null) http.shutdown()
                track
            } catch (_: Exception) {
                http.shutdown()
                null
            }
        }

        override fun makeShallowClone(): AudioTrack = SoundCloudYtDlpTrack(media, manager)
    }

    private inner class SoundCloudNativeFfmpegTrack(
        trackInfo: AudioTrackInfo,
        private val nativeSource: SoundCloudAudioSourceManager,
    ) : DelegatedAudioTrack(trackInfo) {
        override fun getSourceManager(): AudioSourceManager = this@SoundCloudFallbackAudioSourceManager
        override fun isSeekable(): Boolean = info.length > 0
        override fun getDuration(): Long = info.length

        override fun process(executor: LocalAudioTrackExecutor) {
            val lookupUrl = info.identifier.removePrefix(FFMPEG_FORMAT_PREFIX)
            val separator = if ('?' in lookupUrl) "&" else "?"
            val requestUrl = "$lookupUrl${separator}client_id=${nativeSource.clientId}"
            val streamUrl = nativeSource.httpInterface.use { http ->
                http.execute(HttpGet(requestUrl)).use { response ->
                    val status = response.statusLine.statusCode
                    check(status in 200..299) { "SoundCloud transcoding lookup failed with HTTP $status" }
                    val body = response.entity.content.readAllBytes().toString(Charsets.UTF_8)
                    val json = JsonParser.parseString(body).asJsonObject
                    text(json, "url").takeIf { it.isNotBlank() }
                        ?: error("SoundCloud transcoding lookup returned no stream URL")
                }
            }
            getUserData(PlaybackControls::class.java)?.let {
                it.sourceAvailableFormats = "HLS/AAC"
                it.sourceFormatDescription = "HLS/AAC · SoundCloud (FFmpeg)"
            }
            processFfmpeg(streamUrl, emptyMap(), executor)
        }

        override fun makeShallowClone(): AudioTrack = SoundCloudNativeFfmpegTrack(info, nativeSource)
    }

    private fun wrapNativeItem(item: AudioItem): AudioItem {
        val scSource = native as? SoundCloudAudioSourceManager ?: return item
        return when (item) {
            is AudioTrack -> when {
                item.identifier.startsWith(ENCRYPTED_FORMAT_PREFIX) ->
                    throw IllegalStateException("This SoundCloud track is DRM-encrypted (Go+ / restricted) and cannot be streamed")
                item.identifier.startsWith(FFMPEG_FORMAT_PREFIX) ->
                    SoundCloudNativeFfmpegTrack(item.info, scSource)
                else -> item
            }
            is AudioPlaylist -> {
                val playable = item.tracks
                    .filterNot { it.identifier.startsWith(ENCRYPTED_FORMAT_PREFIX) }
                    .map { track ->
                        if (track.identifier.startsWith(FFMPEG_FORMAT_PREFIX)) {
                            SoundCloudNativeFfmpegTrack(track.info, scSource)
                        } else {
                            track
                        }
                    }
                val selected = item.selectedTrack?.let { sel ->
                    playable.firstOrNull { it.identifier == sel.identifier }
                } ?: playable.firstOrNull()
                BasicAudioPlaylist(item.name, playable, selected, item.isSearchResult)
            }
            else -> item
        }
    }

    private fun processFfmpeg(streamUrl: String, headers: Map<String, String>, executor: LocalAudioTrackExecutor) {
        val pipeline = AudioPipelineFactory.create(executor.processingContext, PcmFormat(2, 48000))
        var positionMs = 0L
        try {
            LocalStreamProxy(streamUrl, headers).use { proxy ->
                executor.executeProcessingLoop({
                    val command = mutableListOf(ffmpegExecutable().ifBlank { "ffmpeg" }, "-hide_banner", "-nostdin", "-v", "warning")
                    if (positionMs > 0) command.addAll(listOf("-ss", String.format(Locale.US, "%.3f", positionMs / 1000.0)))
                    command.addAll(
                        listOf(
                            "-protocol_whitelist", "file,http,tcp,crypto,data",
                            "-allowed_extensions", "ALL",
                            "-i", proxy.entryUrl,
                            "-vn", "-sn", "-dn",
                            "-ac", "2", "-ar", "48000",
                            "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1",
                        )
                    )
                val process = ProcessBuilder(command).start()
                val stderr = Capture(process.errorStream, 16 * 1024, process)
                val errThread = thread(isDaemon = true, name = "disc-soundcloud-ffmpeg-err") { stderr.read() }
                val chunks = ArrayBlockingQueue<ByteArray>(4)
                val readerError = arrayOfNulls<Exception>(1)
                val reader = Thread({
                    try {
                        process.inputStream.use { input ->
                            while (true) {
                                val bytes = input.readNBytes(3840)
                                if (bytes.isEmpty()) break
                                chunks.put(bytes)
                            }
                        }
                        chunks.put(ByteArray(0))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: Exception) {
                        readerError[0] = e
                        try { chunks.put(ByteArray(0)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }, "disc-soundcloud-ffmpeg").apply { isDaemon = true; start() }
                try {
                    while (true) {
                        val chunk = chunks.poll(20, TimeUnit.SECONDS)
                            ?: throw IOException("FFmpeg SoundCloud stream timed out")
                        readerError[0]?.let { throw IOException("Cannot read SoundCloud FFmpeg stream", it) }
                        if (chunk.isEmpty()) break
                        if (chunk.size % 4 != 0) throw IOException("Incomplete PCM audio sample")
                        val samples = ShortArray(chunk.size / 2)
                        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        pipeline.process(samples, 0, samples.size)
                    }
                    if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                        errThread.join(1000)
                        val exitCode = runCatching { process.exitValue() }.getOrNull()
                        val fullStderr = stderr.text().trim()
                        logger.warn("SoundCloud FFmpeg decode failed (bin=${command.firstOrNull()}, exit=$exitCode): ${fullStderr.ifBlank { "<no stderr>" }}")
                        val detail = fullStderr.lines().lastOrNull { it.isNotBlank() }?.take(240)
                        val msg = if (detail != null) {
                            "FFmpeg could not decode SoundCloud stream (exit=$exitCode): $detail"
                        } else {
                            "FFmpeg could not decode SoundCloud stream (exit=$exitCode)"
                        }
                        throw IOException(msg)
                    }
                    pipeline.flush()
                } finally {
                    process.destroyForcibly()
                    reader.interrupt()
                    errThread.interrupt()
                    reader.join(1000)
                    errThread.join(500)
                }
            }, { positionMs = it })
            }
        } finally {
            pipeline.close()
        }
    }

    /**
     * Proxies remote HLS playlists, encryption keys, and segments over loopback HTTP so static
     * FFmpeg builds never perform DNS lookups or TLS handshakes (which can SIGSEGV with exit 139).
     */
    private class LocalStreamProxy(
        private val targetUrl: String,
        private val headers: Map<String, String>,
    ) : Closeable {
        private val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "disc-sc-proxy").apply { isDaemon = true }
        }
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = this@LocalStreamProxy.executor
        }
        private val baseUrl = "http://127.0.0.1:${server.address.port}"
        val entryUrl: String = "$baseUrl/entry.m3u8"

        init {
            server.createContext("/entry.m3u8") { exchange ->
                try {
                    val conn = openConnection(targetUrl)
                    val status = conn.responseCode
                    val contentType = conn.contentType.orEmpty()
                    val bodyBytes = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readAllBytes() }
                        ?: ByteArray(0)
                    val bodyText = bodyBytes.toString(Charsets.UTF_8)
                    val isPlaylist = contentType.contains("mpegurl", ignoreCase = true) || bodyText.trimStart().startsWith("#EXTM3U")
                    if (status in 200..299 && isPlaylist) {
                        val rewritten = rewritePlaylist(bodyText, URI(targetUrl)).toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                        exchange.sendResponseHeaders(200, rewritten.size.toLong())
                        exchange.responseBody.use { it.write(rewritten) }
                    } else {
                        if (contentType.isNotBlank()) exchange.responseHeaders.set("Content-Type", contentType)
                        exchange.sendResponseHeaders(status, bodyBytes.size.toLong())
                        exchange.responseBody.use { it.write(bodyBytes) }
                    }
                } catch (_: Exception) {
                    runCatching { exchange.sendResponseHeaders(502, -1) }
                } finally {
                    exchange.close()
                }
            }
            server.createContext("/fetch") { exchange ->
                try {
                    val rawQuery = exchange.requestURI.rawQuery.orEmpty()
                    val encoded = rawQuery.split('&').firstOrNull { it.startsWith("u=") }?.substringAfter('=')
                    val url = encoded?.let { URLDecoder.decode(it, Charsets.UTF_8) }
                    if (url == null || URI(url).scheme !in listOf("http", "https")) {
                        exchange.sendResponseHeaders(400, -1)
                        return@createContext
                    }
                    val conn = openConnection(url)
                    val status = conn.responseCode
                    val len = conn.contentLengthLong
                    conn.contentType?.takeIf { it.isNotBlank() }?.let { exchange.responseHeaders.set("Content-Type", it) }
                    exchange.sendResponseHeaders(status, if (len >= 0) len else 0L)
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    stream?.use { input ->
                        exchange.responseBody.use { out -> input.copyTo(out) }
                    }
                } catch (_: Exception) {
                    runCatching { exchange.sendResponseHeaders(502, -1) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        private fun openConnection(url: String): HttpURLConnection {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            return conn
        }

        private fun rewritePlaylist(playlist: String, base: URI): String {
            val uriAttrRegex = Regex("""URI="([^"]+)"""")
            return playlist.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> uriAttrRegex.replace(line) { match ->
                        val resolved = base.resolve(match.groupValues[1]).toString()
                        """URI="${proxyFetchUrl(resolved)}""""
                    }
                    else -> proxyFetchUrl(base.resolve(trimmed).toString())
                }
            }
        }

        private fun proxyFetchUrl(remoteUrl: String): String =
            "$baseUrl/fetch?u=${URLEncoder.encode(remoteUrl, Charsets.UTF_8)}"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
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
            val stderr = Capture(proc.errorStream, 16 * 1024, proc)
            val outThread = thread(isDaemon = true, name = "disc-sc-ytdlp-out") { stdout.read() }
            val errThread = thread(isDaemon = true, name = "disc-sc-ytdlp-err") { stderr.read() }
            check(proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "SoundCloud extraction timed out after ${timeoutSeconds}s" }
            outThread.join(1000)
            errThread.join(1000)
            check(!stdout.overflow) { "SoundCloud extractor response exceeded the size limit" }
            check(proc.exitValue() == 0) {
                val errDetail = stderr.text().trim().lines().lastOrNull()?.take(200).orEmpty()
                if (errDetail.isNotEmpty()) {
                    "SoundCloud extraction failed with exit code ${proc.exitValue()}: $errDetail"
                } else {
                    "SoundCloud extraction failed with exit code ${proc.exitValue()}"
                }
            }
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

    /**
     * Wraps [DefaultSoundCloudFormatHandler] so tracks lacking progressive/Opus/MP3 transcodings
     * (such as SoundCloud's newer HLS/AAC `audio/mp4` streams) fall back to FFmpeg decoding
     * instead of crashing search results or single-track loads.
     */
    private class FfmpegFallbackSoundCloudFormatHandler(
        private val delegate: DefaultSoundCloudFormatHandler = DefaultSoundCloudFormatHandler(),
    ) : SoundCloudFormatHandler {
        override fun chooseBestFormat(formats: List<SoundCloudTrackFormat>): SoundCloudTrackFormat =
            try {
                delegate.chooseBestFormat(formats)
            } catch (_: RuntimeException) {
                formats.firstOrNull { !isEncrypted(it) && it.lookupUrl.isNotBlank() && it.protocol.equals("hls", ignoreCase = true) }
                    ?: formats.firstOrNull { !isEncrypted(it) && it.lookupUrl.isNotBlank() }
                    ?: formats.firstOrNull { it.lookupUrl.isNotBlank() }
                    ?: throw RuntimeException("Did not detect any supported formats")
            }

        override fun buildFormatIdentifier(format: SoundCloudTrackFormat): String = when {
            runCatching { delegate.chooseBestFormat(listOf(format)) }.isSuccess ->
                delegate.buildFormatIdentifier(format)
            isEncrypted(format) -> "$ENCRYPTED_FORMAT_PREFIX${format.lookupUrl}"
            else -> "$FFMPEG_FORMAT_PREFIX${format.lookupUrl}"
        }

        private fun isEncrypted(format: SoundCloudTrackFormat): Boolean =
            format.protocol.contains("encrypted", ignoreCase = true) ||
                format.lookupUrl.contains("encrypted", ignoreCase = true)

        override fun getM3uInfo(identifier: String): SoundCloudM3uInfo? = delegate.getM3uInfo(identifier)

        override fun getMp3LookupUrl(identifier: String): String? = delegate.getMp3LookupUrl(identifier)
    }

    companion object {
        private const val FFMPEG_FORMAT_PREFIX = "FFMPEG:"
        private const val ENCRYPTED_FORMAT_PREFIX = "ENCRYPTED:"
        private const val AUDIO_FORMAT = "bestaudio[protocol!=m3u8_native][protocol!=m3u8]/bestaudio/best"

        private fun createNativeSourceManager(): SoundCloudAudioSourceManager =
            SoundCloudAudioSourceManager.builder()
                .withAllowSearch(true)
                .withFormatHandler(FfmpegFallbackSoundCloudFormatHandler())
                .build()

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
