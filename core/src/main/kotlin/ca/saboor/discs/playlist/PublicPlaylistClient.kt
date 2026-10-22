package ca.saboor.discs.playlist

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Reads metadata already shipped to anonymous visitors of the public playlist pages. */
class PublicPlaylistClient(private val fetch: (URI) -> String = ::fetchPage) {
    data class Result(val songs: List<CatalogPlaylistClient.Song>, val notice: String? = null)

    fun load(reference: CatalogPlaylistClient.Reference): Result {
        val path = reference.path.substringBefore('?').split('/').filter(String::isNotBlank)
        val uri = if (reference.service == "Spotify") {
            val id = path[2]
            require(id.matches(Regex("[A-Za-z0-9]+")))
            URI("https://open.spotify.com/embed/playlist/$id")
        } else {
            val country = path[2]
            val id = path[4]
            require(country.matches(Regex("[a-z]{2}")) && id.matches(Regex("pl\\.[A-Za-z0-9-]+")))
            URI("https://music.apple.com/$country/playlist/$id")
        }
        val html = fetch(uri)
        return if (reference.service == "Spotify") spotify(html) else apple(html)
    }

    companion object {
        private fun text(value: JsonObject, key: String): String = value.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

        private fun script(html: String, id: String): JsonElement {
            val scripts = Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val idAttribute = Regex("(?:^|\\s)id\\s*=\\s*(?:\"${Regex.escape(id)}\"|'${Regex.escape(id)}'|${Regex.escape(id)}(?=\\s|$))", RegexOption.IGNORE_CASE)
            val body = scripts.findAll(html).firstOrNull { idAttribute.containsMatchIn(it.groupValues[1]) }?.groupValues?.get(2)
                ?: error("Public playlist data is unavailable. The playlist may be private, region restricted, or the website format may have changed.")
            return JsonParser.parseString(body)
        }

        private fun spotify(html: String): Result {
            val root = script(html, "__NEXT_DATA__").asJsonObject
            val entity = root.getAsJsonObject("props")?.getAsJsonObject("pageProps")
                ?.getAsJsonObject("state")?.getAsJsonObject("data")?.getAsJsonObject("entity")
                ?: error("Spotify public playlist is unavailable. Check that the playlist is public and accessible in the server's region.")
            require(text(entity, "type") == "playlist") { "Spotify did not return a playlist." }
            val rows = entity.getAsJsonArray("trackList") ?: error("Spotify did not expose any playlist songs.")
            val songs = rows.mapNotNull { row ->
                if (!row.isJsonObject) return@mapNotNull null
                val item = row.asJsonObject
                if (!text(item, "uri").startsWith("spotify:track:")) return@mapNotNull null
                song(text(item, "title"), text(item, "subtitle"))
            }
            require(songs.isNotEmpty()) { "Spotify public playlist contains no readable songs." }
            return Result(songs, if (rows.size() >= 100)
                "Spotify's public embed exposes at most 100 entries. Importing ${songs.size} readable songs from that preview; a longer playlist will not be imported in full. Configure optional Spotify API access for full pagination."
                else null)
        }

        private fun apple(html: String): Result {
            val root = script(html, "serialized-server-data")
            val sections = mutableListOf<JsonObject>()
            var expectedCount: Int? = null
            fun visit(value: JsonElement) {
                if (value.isJsonArray) value.asJsonArray.forEach(::visit)
                else if (value.isJsonObject) {
                    val obj = value.asJsonObject
                    if (text(obj, "itemKind") == "trackLockup") sections += obj
                    if (text(obj, "itemKind") == "containerDetailHeaderLockup") {
                        obj.getAsJsonArray("items")?.forEach { item ->
                            item.asJsonObject.get("trackCount")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                                ?.asInt?.let { expectedCount = it }
                        }
                    }
                    obj.entrySet().forEach { visit(it.value) }
                }
            }
            visit(root)
            val songs = sections.flatMap { section ->
                section.getAsJsonArray("items")?.mapNotNull { row ->
                    val item = row.asJsonObject
                    if (text(item.getAsJsonObject("contentDescriptor") ?: JsonObject(), "kind") != "song") return@mapNotNull null
                    val artists = item.getAsJsonArray("subtitleLinks")?.map { text(it.asJsonObject, "title") }
                        ?.filter(String::isNotBlank)?.joinToString(", ").orEmpty()
                    song(text(item, "title"), artists)
                }.orEmpty()
            }
            require(songs.isNotEmpty()) { "Apple Music public playlist contains no readable songs." }
            require(songs.size <= 1000) { "Playlist exceeds the 1000-song import limit." }
            return Result(songs, expectedCount?.takeIf { it > songs.size }?.let {
                "Apple Music's public page exposed ${songs.size} of $it songs. Importing only the readable public entries; configure optional API access for full pagination."
            })
        }

        private fun song(title: String, artist: String): CatalogPlaylistClient.Song? =
            if (title.isBlank() || artist.isBlank()) null else CatalogPlaylistClient.Song(title, artist.replace('\u00a0', ' '))

        private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build() }
        private fun fetchPage(uri: URI): String {
            var target = uri
            repeat(5) {
                require(target.scheme == "https" && target.host == uri.host && target.port == -1 && target.userInfo == null) {
                    "Invalid public playlist redirect."
                }
                val request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html").header("Accept-Language", "en-US,en;q=0.9").GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                        target = target.resolve(response.headers().firstValue("Location").orElseThrow {
                            IllegalStateException("Public playlist redirect has no destination.")
                        })
                    } else {
                        check(response.statusCode() == 200) { "Public playlist page unavailable (HTTP ${response.statusCode()}). Check visibility and region, or try again later." }
                        val bytes = body.readNBytes(8 * 1024 * 1024 + 1)
                        check(bytes.size <= 8 * 1024 * 1024) { "Public playlist page is too large." }
                        return bytes.toString(Charsets.UTF_8)
                    }
                }
            }
            error("Too many public playlist redirects.")
        }
    }
}
