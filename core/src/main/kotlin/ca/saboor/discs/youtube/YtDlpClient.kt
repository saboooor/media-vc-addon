package ca.saboor.discs.youtube

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One extraction service for audio and screen video. Never invokes a command shell. */
internal class YtDlpClient(
    private val executable: String,
    private val configFile: String = "",
    private val timeoutSeconds: Long = 25,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    data class Media(val id: String, val title: String, val author: String, val durationMs: Long,
        val live: Boolean, val url: String?, val headers: Map<String, String>, val format: String) {
        val watchUrl get() = "https://www.youtube.com/watch?v=$id"
    }
    private data class Cached(val media: Media, val until: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<Media>>()
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    private val slots = Semaphore(2)
    private val closed = AtomicBoolean()

    fun audio(id: String): Media = resolve(id, "bestaudio/best")
    fun video(id: String, height: Int): Media {
        val limit = height.coerceIn(144, 2160)
        return resolve(id, "bestvideo[height<=$limit]/best[height<=$limit]")
    }

    private fun resolve(id: String, format: String): Media {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid YouTube video ID" }
        val key = "$id:$format"
        cache[key]?.takeIf { clock() < it.until }?.let { return it.media }
        val result = CompletableFuture<Media>()
        pending.putIfAbsent(key, result)?.let { return it.get(timeoutSeconds + 5, TimeUnit.SECONDS) }
        try {
            val json = run(listOf("--no-playlist", "-f", format, "--", "https://www.youtube.com/watch?v=$id"))
            val media = parseMedia(json)
            require(media.id == id && media.url != null) { "yt-dlp did not return a playable YouTube stream" }
            val expires = URI(media.url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }
                ?.substringAfter('=')?.toLongOrNull()?.let { it * 1000 - 60_000 } ?: Long.MAX_VALUE
            if (cache.size >= 128) cache.clear()
            cache[key] = Cached(media, minOf(clock() + 5 * 60_000, expires))
            result.complete(media)
            return media
        } catch (e: Exception) {
            result.completeExceptionally(e)
            throw e
        } finally { pending.remove(key, result) }
    }

    fun playlist(identifier: String): Pair<String, List<Media>> {
        val target = if (identifier.startsWith("ytsearch:") || identifier.startsWith("ytmsearch:"))
            "ytsearch10:${identifier.substringAfter(':')}" else identifier
        val json = run(listOf("--yes-playlist", "--flat-playlist", "--playlist-end", "500", "--", target))
        val entries = json.getAsJsonArray("entries")?.mapNotNull { entry ->
            runCatching { parseMedia(entry.asJsonObject, flat = true) }.getOrNull()
        }.orEmpty()
        return text(json, "title", "YouTube") to entries
    }

    private fun run(arguments: List<String>): JsonObject {
        check(!closed.get()) { "YouTube extractor is stopped" }
        check(slots.tryAcquire(2, TimeUnit.SECONDS)) { "YouTube extractor is busy; retry shortly" }
        var process: Process? = null
        try {
            check(!closed.get()) { "YouTube extractor is stopped" }
            val command = mutableListOf(executable, "--ignore-config")
            if (configFile.isNotBlank()) command.addAll(listOf("--config-locations", configFile))
            command.addAll(listOf("--dump-single-json", "--skip-download", "--no-progress", "--no-warnings",
                "--socket-timeout", "10", "--retries", "1", "--extractor-retries", "1"))
            command.addAll(arguments)
            val proc = ProcessBuilder(command).start()
            process = proc
            processes.add(proc)
            if (closed.get()) { proc.destroyForcibly(); error("YouTube extractor is stopped") }
            proc.outputStream.close()
            val stdout = Capture(proc.inputStream, 8 * 1024 * 1024, proc, true)
            val stderr = Capture(proc.errorStream, 64 * 1024, proc, false)
            val outThread = thread(isDaemon = true, name = "disc-ytdlp-output") { stdout.read() }
            val errThread = thread(isDaemon = true, name = "disc-ytdlp-error") { stderr.read() }
            check(proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "YouTube extraction timed out after ${timeoutSeconds}s" }
            outThread.join(1000); errThread.join(1000)
            check(!outThread.isAlive && !errThread.isAlive) { "YouTube extractor output did not close" }
            check(!stdout.overflow) { "YouTube extractor response exceeded the size limit" }
            if (proc.exitValue() != 0) {
                // Do not expose cookies, signed URLs or config-file diagnostics to players/logs.
                val issue = ca.saboor.discs.audio.PlaybackFailure.youtubeIssue(stderr.text())
                val reason = when (issue) {
                    ca.saboor.discs.audio.PlaybackFailure.YoutubeIssue.RATE_LIMIT -> "YouTube rate limit exceeded; retry later"
                    ca.saboor.discs.audio.PlaybackFailure.YoutubeIssue.BOT_CHECK -> "YouTube bot verification required; check the yt-dlp PO-token provider"
                    else -> "YouTube extraction failed; check yt-dlp, its configured PO-token provider and content availability"
                }
                throw IllegalStateException(reason)
            }
            return JsonParser.parseString(stdout.text()).asJsonObject
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Could not run yt-dlp; check youtube_source.ytdlp_path and executable permissions")
        } finally {
            process?.let { proc ->
                proc.descendants().forEach { it.destroyForcibly() }
                if (proc.isAlive) proc.destroyForcibly()
                processes.remove(proc)
                runCatching { proc.inputStream.close(); proc.errorStream.close() }
            }
            slots.release()
        }
    }

    private class Capture(val input: InputStream, val limit: Int, val process: Process, val stopOnOverflow: Boolean) {
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
                        if (retained < count) { overflow = true; if (stopOnOverflow) { process.destroyForcibly(); break } }
                    }
                }
            }
        }
        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    override fun close() {
        closed.set(true)
        processes.forEach { proc -> proc.descendants().forEach { it.destroyForcibly() }; proc.destroyForcibly() }
        cache.clear()
    }

    companion object {
        private fun text(json: JsonObject, key: String, fallback: String = "") =
            json.get(key)?.takeUnless { it.isJsonNull }?.asString ?: fallback
        internal fun parseMedia(json: JsonObject, flat: Boolean = false): Media {
            val id = text(json, "id")
            require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
            val url = if (flat) null else text(json, "url").takeIf { it.isNotBlank() }
            url?.let { require(URI(it).scheme in listOf("http", "https") && URI(it).host != null) }
            val headers = if (flat) emptyMap() else json.getAsJsonObject("http_headers")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            return Media(id, text(json, "title", id), text(json, "uploader", text(json, "channel", "YouTube")),
                ((json.get("duration")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0) * 1000).toLong(),
                json.get("is_live")?.takeUnless { it.isJsonNull }?.asBoolean ?: (text(json, "live_status") == "is_live"),
                url, headers, text(json, "format", "yt-dlp audio"))
        }
    }
}
