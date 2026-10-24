package ca.saboor.discs.jellyfin

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
    data class Media(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long, val type: String = "Audio",
                     val seasonNumber: Int? = null, val episodeNumber: Int? = null) {
        val isSeries get() = type == "Series"
        val browserLabel get() = if (type == "Episode") {
            val season = seasonNumber?.let { "S${it.toString().padStart(2, '0')}" } ?: "S?"
            val episode = episodeNumber?.let { "E${it.toString().padStart(2, '0')}" } ?: "E?"
            "$season$episode · $label"
        } else label
        val label get() = ca.saboor.discs.utils.extend.formatTrackDisplay(title, artist)
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

    fun browse(session: Session, page: Int, search: String = "", limit: Int = PAGE_SIZE): Page {
        require(page in 0..100_000 && search.length <= 200 && limit in 1..PAGE_SIZE)
        val query = "Items?userId=${session.userId}&recursive=true&includeItemTypes=Audio,AudioBook,Movie,Series,MusicVideo,Video&sortBy=SortName&sortOrder=Ascending" +
            "&startIndex=${page * limit}&limit=$limit&enableImages=false&searchTerm=${encode(search)}"
        return mediaPage(session, query, limit)
    }

    /** Sort the entire series numerically on the server before applying pagination. */
    fun episodes(session: Session, seriesId: String, page: Int, search: String = "", limit: Int = PAGE_SIZE): Page {
        require(page in 0..100_000 && search.length <= 200 && limit in 1..PAGE_SIZE)
        val query = "Items?userId=${session.userId}&parentId=${mediaId(seriesId)}&recursive=true&includeItemTypes=Episode" +
            "&sortBy=ParentIndexNumber,IndexNumber,SortName&sortOrder=Ascending&isMissing=false" +
            "&startIndex=${page * limit}&limit=$limit&enableImages=false&enableTotalRecordCount=true&searchTerm=${encode(search)}"
        return mediaPage(session, query, limit)
    }

    private fun mediaPage(session: Session, query: String, limit: Int): Page {
        val json = request(session.baseUrl, query, session.deviceId, session.token)
        val items = json.getAsJsonArray("Items")?.take(limit)?.map { value ->
            val item = value.asJsonObject
            Media(mediaId(item.get("Id").asString), (item.get("Name")?.asString ?: "Untitled").take(256),
                (item.get("Artists")?.takeUnless { it.isJsonNull }?.asJsonArray?.joinToString(", ") { it.asString } ?: "").take(256),
                item.get("Album")?.takeUnless { it.isJsonNull }?.asString ?: "",
                (item.get("RunTimeTicks")?.takeUnless { it.isJsonNull }?.asLong ?: 0L) / 10_000, item.get("Type")?.takeUnless { it.isJsonNull }?.asString ?: "Audio",
                item.get("ParentIndexNumber")?.takeUnless { it.isJsonNull }?.asInt,
                item.get("IndexNumber")?.takeUnless { it.isJsonNull }?.asInt)
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

    data class AudioTrack(val index: Int, val displayTitle: String, val language: String,
                          val isDefault: Boolean, val mediaSourceId: String)

    fun getAudioTracks(session: Session, itemId: String): List<AudioTrack> {
        val json = request(session.baseUrl, "Items/${mediaId(itemId)}/PlaybackInfo?userId=${session.userId}", session.deviceId, session.token)
        val source = json.getAsJsonArray("MediaSources")?.firstOrNull()?.asJsonObject ?: return emptyList()
        val sourceId = source.get("Id")?.asString ?: return emptyList()
        return source.getAsJsonArray("MediaStreams")?.mapNotNull { element ->
            val obj = element.asJsonObject
            fun text(key: String) = obj.get(key)?.takeUnless { it.isJsonNull }?.asString
            if (!text("Type").equals("Audio", true)) return@mapNotNull null
            val index = obj.get("Index")?.takeUnless { it.isJsonNull }?.asInt ?: return@mapNotNull null
            if (index < 0) return@mapNotNull null
            AudioTrack(index, text("DisplayTitle") ?: "Audio $index", text("Language") ?: "und",
                obj.get("IsDefault")?.takeUnless { it.isJsonNull }?.asBoolean ?: false, sourceId)
        }.orEmpty()
    }

    /** The video route maps the requested stream; Jellyfin's audio route can ignore it for movies. */
    fun selectedAudioStreamUrl(session: Session, itemId: String, track: AudioTrack, positionMs: Long): String {
        require(track.index >= 0)
        return session.baseUrl + "Videos/${mediaId(itemId)}/stream.mkv?static=false" +
            "&mediaSourceId=${URLEncoder.encode(track.mediaSourceId, Charsets.UTF_8)}&audioStreamIndex=${track.index}" +
            "&videoCodec=copy&allowVideoStreamCopy=true&audioCodec=aac&allowAudioStreamCopy=false" +
            "&audioBitRate=192000&audioChannels=2&subtitleStreamIndex=-1" +
            "&startTimeTicks=${positionMs.coerceIn(0, Long.MAX_VALUE / 10_000) * 10_000}&playSessionId=${UUID.randomUUID()}"
    }

    data class SubtitleTrack(
        val index: Int,
        val displayTitle: String,
        val language: String,
        val isDefault: Boolean,
        val isForced: Boolean,
        val codec: String,
        val deliveryUrl: String?,
        val mediaSourceId: String = ""
    )

    fun getSubtitleTracks(session: Session, itemId: String): List<SubtitleTrack> {
        val json = request(session.baseUrl, "Items/${mediaId(itemId)}/PlaybackInfo?userId=${session.userId}", session.deviceId, session.token)
        val source = json.getAsJsonArray("MediaSources")?.firstOrNull()?.asJsonObject ?: return emptyList()
        val sourceId = source.get("Id")?.asString ?: return emptyList()
        val streams = source.getAsJsonArray("MediaStreams") ?: return emptyList()
        fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
        return streams.mapNotNull { element ->
            val obj = element.asJsonObject
            if (!obj.string("Type").equals("Subtitle", true)) return@mapNotNull null
            val codec = obj.string("Codec") ?: ""
            // Image subtitles require OCR and cannot be represented by a TextDisplay.
            if (obj.get("IsTextSubtitleStream")?.takeUnless { it.isJsonNull }?.asBoolean == false ||
                codec.lowercase() in setOf("pgssub", "hdmv_pgs_subtitle", "dvdsub", "dvd_subtitle", "dvb_subtitle", "xsub")) return@mapNotNull null
            val index = obj.get("Index")?.asInt ?: return@mapNotNull null
            SubtitleTrack(index, obj.string("DisplayTitle") ?: "Subtitle $index", obj.string("Language") ?: "und",
                obj.get("IsDefault")?.asBoolean ?: false, obj.get("IsForced")?.asBoolean ?: false, codec, null, sourceId)
        }
    }

    fun downloadSubtitle(session: Session, itemId: String, track: SubtitleTrack): String? {
        require(track.index >= 0)
        // Use the actual source ID and explicitly request converted SRT. Never send tokens to DeliveryUrl hosts.
        require(track.mediaSourceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val path = "Videos/${mediaId(itemId)}/${track.mediaSourceId}/Subtitles/${track.index}/0/Stream.srt?copyTimestamps=true"
        val req = HttpRequest.newBuilder(URI.create(session.baseUrl + path))
            .header("Authorization", authorization(session.deviceId, session.token))
            .timeout(Duration.ofSeconds(30)).GET().build()
        val response = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        return response.body().use { input ->
            if (response.statusCode() !in 200..299) throw JellyfinException("Jellyfin subtitle download failed (HTTP ${response.statusCode()}).")
            val bytes = input.readNBytes(4 * 1024 * 1024 + 1)
            if (bytes.size > 4 * 1024 * 1024) throw JellyfinException("Subtitle file is too large.")
            String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
    }

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

