package su.plo.voice.discs.jellyfin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class JellyfinException(message: String) : RuntimeException(message)

/** Passwords are exchanged once for a user token, never written to disk or placed in URLs. */
class JellyfinClient(private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
    class Session(val baseUrl: String, val userId: String, val token: String, val deviceId: String) {
        override fun toString() = "Jellyfin session"
    }
    data class Media(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long, val type: String = "Audio") {
        val label get() = su.plo.voice.discs.utils.extend.formatTrackDisplay(title, artist)
    }
    data class Page(val items: List<Media>, val total: Int)

    fun login(url: String, username: String, password: String, deviceId: String): Session {
        val base = normalizeUrl(url)
        val body = JsonObject().apply { addProperty("Username", username); addProperty("Pw", password) }
        val json = request(base, "Users/AuthenticateByName", deviceId, body = body)
        val token = json.get("AccessToken")?.asString ?: throw JellyfinException("Jellyfin did not return an access token.")
        require(token.matches(Regex("[A-Za-z0-9._-]{1,1024}"))) { "Invalid Jellyfin access token." }
        val userId = mediaId(json.getAsJsonObject("User")?.get("Id")?.asString ?: "")
        return Session(base, userId, token, deviceId)
    }

    fun browse(session: Session, page: Int, search: String = ""): Page {
        require(page in 0..100_000 && search.length <= 200)
        val query = "Items?userId=${session.userId}&recursive=true&includeItemTypes=Audio,AudioBook,Movie,Episode,MusicVideo,Video&sortBy=SortName&sortOrder=Ascending" +
            "&startIndex=${page * PAGE_SIZE}&limit=$PAGE_SIZE&enableImages=false&searchTerm=${encode(search)}"
        val json = request(session.baseUrl, query, session.deviceId, session.token)
        val items = json.getAsJsonArray("Items")?.take(PAGE_SIZE)?.map { value ->
            val item = value.asJsonObject
            Media(mediaId(item.get("Id").asString), (item.get("Name")?.asString ?: "Untitled").take(256),
                (item.getAsJsonArray("Artists")?.joinToString(", ") { it.asString } ?: "").take(256),
                item.get("Album")?.takeUnless { it.isJsonNull }?.asString ?: "",
                (item.get("RunTimeTicks")?.takeUnless { it.isJsonNull }?.asLong ?: 0L) / 10_000, item.get("Type")?.takeUnless { it.isJsonNull }?.asString ?: "Audio")
        } ?: emptyList()
        return Page(items, json.get("TotalRecordCount")?.asInt ?: items.size)
    }

    fun logout(session: Session) { request(session.baseUrl, "Sessions/Logout", session.deviceId, session.token, JsonObject()) }

    fun streamUrl(session: Session, itemId: String, type: String = "Audio", startPositionMs: Long = 0): String =
        session.baseUrl + "Audio/${mediaId(itemId)}/stream" +
            if (type in listOf("Audio", "AudioBook")) "?static=true"
            else "?container=mp3&audioCodec=mp3&audioBitRate=192000&audioChannels=2&static=false" +
                "&startTimeTicks=${startPositionMs.coerceIn(0, Long.MAX_VALUE / 10_000) * 10_000}" +
                // Jellyfin keys progressive transcode files by session, not startTimeTicks.
                "&playSessionId=${java.util.UUID.randomUUID()}"

    fun videoStreamUrl(session: Session, itemId: String): String =
        session.baseUrl + "Videos/${mediaId(itemId)}/stream?static=true"

    private fun request(base: String, path: String, device: String, token: String? = null, body: JsonObject? = null): JsonObject {
        try {
            val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization(device, token)).header("Accept", "application/json")
            if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                if (response.statusCode() !in 200..299) throw JellyfinException(when (response.statusCode()) {
                    401, 403 -> "Jellyfin rejected the login or access token. Connect again."
                    in 300..399 -> "Use the final Jellyfin server URL; redirects are not followed."
                    else -> "Jellyfin request failed (HTTP ${response.statusCode()})."
                })
                val bytes = input.readNBytes(2_097_153)
                if (bytes.size > 2_097_152) throw JellyfinException("Jellyfin response is too large.")
                if (bytes.isEmpty()) return JsonObject()
                return JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            }
        } catch (e: JellyfinException) { throw e }
        catch (_: Exception) { throw JellyfinException("Could not reach Jellyfin or read its response. Check the server URL and connection.") }
    }

    companion object {
        const val PAGE_SIZE = 45
        fun normalizeUrl(value: String): String {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
                ?: throw JellyfinException("Enter a valid http:// or https:// Jellyfin URL.")
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535)
                throw JellyfinException("Enter an HTTP(S) server URL without credentials, query parameters, or fragments.")
            return uri.toASCIIString().trimEnd('/') + "/"
        }
        fun mediaId(value: String): String {
            val hex = value.replace("-", "")
            require(hex.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid Jellyfin media ID." }
            return hex.lowercase(java.util.Locale.ROOT)
        }
        fun authorization(device: String, token: String? = null): String {
            require(device.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            require(token == null || token.matches(Regex("[A-Za-z0-9._-]{1,1024}")))
            return "MediaBrowser Client=\"vc-addon-discs\", Device=\"Minecraft\", DeviceId=\"$device\", Version=\"1.0\"" +
                (token?.let { ", Token=\"$it\"" } ?: "")
        }
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
