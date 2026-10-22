package ca.saboor.discs.playlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Imports catalog metadata only; playback is resolved through the server's audio sources. */
class CatalogPlaylistClient(
    private val fetch: (URI, String) -> JsonObject = ::fetchJson,
) {
    data class Reference(val service: String, val path: String)
    data class Song(val title: String, val artist: String) {
        val query get() = "$artist - $title official audio"
    }

    fun load(reference: Reference, token: String): List<Song> {
        val service = reference.service
        require(token.isNotBlank()) {
            "Configure playlist_sources.${if (service == "Spotify") "spotify_access_token" else "apple_music_developer_token"} or its server environment variable first."
        }
        val host = if (service == "Spotify") "api.spotify.com" else "api.music.apple.com"
        var next: URI? = URI("https://$host${reference.path}")
        val visited = mutableSetOf<URI>()
        val songs = mutableListOf<Song>()
        while (next != null) {
            val uri = next
            require(uri.scheme == "https" && uri.host == host && uri.port == -1 && uri.userInfo == null) { "Invalid catalog pagination URL." }
            require(visited.add(uri) && visited.size <= 50) { "Playlist has too many pages." }
            val json = fetch(uri, token)
            val page = if (service == "Spotify") json else if (json.has("data") && json.getAsJsonArray("data").firstOrNull()?.asJsonObject?.has("relationships") == true)
                json.getAsJsonArray("data")[0].asJsonObject.getAsJsonObject("relationships").getAsJsonObject("tracks") else json
            val items = page.getAsJsonArray(if (service == "Spotify") "items" else "data")
                ?: error("$service did not return playlist tracks. Check token permissions and playlist access.")
            for (element in items) {
                if (!element.isJsonObject) continue
                val entry = element.asJsonObject
                val song = if (service == "Spotify") {
                    val item = entry.get("item")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: entry.get("track")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    if (item.text("type") != "track" || item.get("is_local")?.asBoolean == true) continue
                    Song(item.text("name"), item.getAsJsonArray("artists")?.map { it.asJsonObject.text("name") }?.joinToString(", ") ?: "")
                } else {
                    if (entry.text("type") != "songs") continue
                    val attributes = entry.getAsJsonObject("attributes") ?: continue
                    Song(attributes.text("name"), attributes.text("artistName"))
                }
                if (song.title.isNotBlank() && song.artist.isNotBlank()) songs += song
                require(songs.size <= 1000) { "Playlist exceeds the 1000-song import limit." }
            }
            next = page.text("next").takeIf { it.isNotBlank() }?.let { uri.resolve(it) }
        }
        require(songs.isNotEmpty()) { "$service playlist contains no accessible songs." }
        return songs
    }

    companion object {
        fun parse(value: String): Reference? {
            if (value.startsWith("spotify:playlist:")) {
                val id = value.removePrefix("spotify:playlist:")
                require(id.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify playlist ID." }
                return Reference("Spotify", "/v1/playlists/$id/items?limit=50")
            }
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host !in setOf("open.spotify.com", "music.apple.com")) return null
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1) { "Use an HTTPS playlist share link." }
            val parts = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
            if (host == "open.spotify.com") {
                val path = if (parts.firstOrNull()?.startsWith("intl-") == true) parts.drop(1) else parts
                require(path.size == 2 && path[0] == "playlist" && path[1].matches(Regex("[A-Za-z0-9]+"))) { "Use a Spotify playlist link." }
                return Reference("Spotify", "/v1/playlists/${path[1]}/items?limit=50")
            }
            require(parts.size >= 3 && parts[0].matches(Regex("[a-zA-Z]{2}")) && parts[1] == "playlist" &&
                parts.last().matches(Regex("pl\\.[A-Za-z0-9-]+"))) { "Use a public Apple Music catalog playlist link with a country code." }
            return Reference("Apple Music", "/v1/catalog/${parts[0].lowercase()}/playlists/${parts.last()}?include=tracks")
        }
        private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString ?: ""
        private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
        private fun fetchJson(uri: URI, token: String): JsonObject {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $token").header("Accept", "application/json").GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                check(response.statusCode() in 200..299) {
                    when (response.statusCode()) {
                        401 -> "Catalog token expired or invalid; update the server playlist token."
                        403, 404 -> "Playlist unavailable to this token. Check playlist visibility, ownership and API permissions."
                        429 -> "Catalog rate limit reached. Try again later."
                        else -> "Catalog request failed (HTTP ${response.statusCode()})."
                    }
                }
                val bytes = body.readNBytes(4 * 1024 * 1024 + 1)
                check(bytes.size <= 4 * 1024 * 1024) { "Catalog response is too large." }
                return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            }
        }
    }
}
