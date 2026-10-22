package su.plo.voice.discs.webhook

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import su.plo.voice.discs.AddonConfig
import su.plo.voice.discs.audio.PlaybackFailure
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Bounded, nonblocking notifications. The configured endpoint receives Discord-compatible JSON. */
class PlaybackWebhooks(
    scope: CoroutineScope,
    private val config: () -> AddonConfig.WebhookConfig,
    private val warn: (String) -> Unit,
    private val authorize: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Delivery(val destination: URI, val text: String)
    private val queue = Channel<Delivery>(32)
    private val recent = linkedMapOf<String, Long>()
    private var authPendingUntil = 0L
    private var pendingPrompt: OauthPrompt? = null
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val worker = scope.launch(Dispatchers.IO) {
        for (delivery in queue) {
            try {
                val response = http.send(HttpRequest.newBuilder(delivery.destination).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload(delivery.text))).build(),
                    HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() !in 200..299) warn("Discs webhook delivery failed (HTTP ${response.statusCode()}).")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { warn("Discs webhook delivery failed. Check its URL and connectivity.") }
        }
    }

    fun failure(identifier: String, message: String, error: Throwable? = null, context: String = "Playback") {
        if (destination() == null) return
        val youtube = isYoutube(identifier)
        val login = youtube && (if (error != null) PlaybackFailure.keepDisc(error) else PlaybackFailure.keepDisc(message)) &&
            !Regex("rate[ -]?limit|too many requests|429", RegexOption.IGNORE_CASE).containsMatchIn(message)
        val source = if (youtube) "YouTube" else if (identifier.startsWith("jellyfin:")) "Jellyfin" else "Media provider"
        post("failure:$source:${if (login) "login" else clean(message)}",
            "$context failed · $source\n${clean(message)}" + if (login) "\nDisc kept. Reauthorize YouTube to retry playback.\nManual command: /disc oauth force" else "")
        if (login) synchronized(this) { pendingPrompt?.takeIf { clock() < authPendingUntil } }?.let(::sendPrompt)
        if (login && config().autoYoutubeOauth) {
            val start = synchronized(this) {
                if (clock() < authPendingUntil) false else { authPendingUntil = clock() + 15 * 60_000; true }
            }
            if (start) {
                // Never run the provider's blocking device-code request on a server or decoder thread.
                authJob = authScope.launch(Dispatchers.IO) {
                    try {
                        if (!authorize()) {
                            clearAuthorization()
                            post("oauth-unavailable", "YouTube OAuth could not start. Enable the YouTube source and run /disc oauth force.")
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        clearAuthorization()
                        post("oauth-failed", "YouTube OAuth could not start. Check the server console, then run /disc oauth force.")
                    }
                }
            }
        }
    }
    private val authScope = scope
    private var authJob: Job? = null

    fun oauthPrompt(prompt: OauthPrompt) {
        synchronized(this) { authPendingUntil = clock() + 15 * 60_000; pendingPrompt = prompt }
        sendPrompt(prompt)
    }
    private fun sendPrompt(prompt: OauthPrompt) {
        post("oauth-prompt:${prompt.code}", "YouTube reauthorization required\nOpen ${prompt.url}\nPairing code: ${prompt.code}\nAuthorize the intended account promptly. On success the plugin saves the replacement token automatically.\nOnly authorize a request from your own server.")
    }
    fun oauthSuccess() {
        clearAuthorization()
        post("oauth-success", "YouTube authorization succeeded. The replacement token was saved; retry playback. Discs were kept.")
    }
    @Synchronized private fun clearAuthorization() { authPendingUntil = 0; pendingPrompt = null }
    fun close() { queue.close(); worker.cancel(); authJob?.cancel() }

    private fun destination(): URI? {
        val value = config().url.trim()
        if (value.isEmpty()) return null
        return runCatching { URI(value).takeIf { it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null && it.fragment == null } }.getOrNull()
    }
    @Synchronized private fun post(key: String, text: String) {
        val destination = destination() ?: return
        val now = clock()
        val cooldown = config().cooldownSeconds.coerceIn(1, 3600) * 1000L
        if (recent[key]?.let { now - it < cooldown } == true) return
        if (recent.size >= 256) recent.remove(recent.keys.first())
        if (queue.trySend(Delivery(destination, text.take(1900))).isSuccess) recent[key] = now
        else warn("Discs webhook queue is full; notification dropped.")
    }

    companion object {
        fun payload(text: String): String = JsonObject().apply {
            addProperty("content", text.take(1900))
            add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
        }.toString()
        fun clean(text: String): String = text
            .replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), "[URL redacted]")
            .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
            .replace(Regex("(?i)(access_token|refresh_token|id_token|client_secret|device_code|password|authorization|po_token|visitor_data|token)[\\\"']?\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)"), "$1=[redacted]")
            .replace(Regex("[\\r\\n\\t]+"), " ").take(800)
        fun isYoutube(identifier: String): Boolean = runCatching {
            val host = URI(identifier).host?.lowercase()
            host == "youtu.be" || host == "youtube.com" || host?.endsWith(".youtube.com") == true ||
                identifier.startsWith("ytsearch:") || identifier.matches(Regex("[A-Za-z0-9_-]{11}"))
        }.getOrDefault(false)
    }
}

data class OauthPrompt(val url: String, val code: String) {
    companion object {
        // Capture only the public verification URL and user-facing pairing code, never device/token responses.
        fun parse(message: String): OauthPrompt? {
            val match = Regex("OAUTH INTEGRATION:.*go to (https://[^\\s]+) and enter code ([A-Za-z0-9-]{4,32})").find(message) ?: return null
            val uri = runCatching { URI(match.groupValues[1]) }.getOrNull() ?: return null
            if (uri.host !in setOf("www.google.com", "google.com", "www.youtube.com", "youtube.com") || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            return OauthPrompt(uri.toASCIIString(), match.groupValues[2])
        }
    }
}
