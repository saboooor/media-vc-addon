package ca.saboor.discs.playlist

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogPlaylistClientTest {
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun `share links recognize both catalogs and reject lookalike hosts`() {
        assertEquals(CatalogPlaylistClient.parse("spotify:playlist:ABC123"),
            CatalogPlaylistClient.parse("https://open.spotify.com/intl-en/playlist/ABC123?si=test"))
        assertEquals("/v1/catalog/ca/playlists/pl.123-abc?include=tracks",
            CatalogPlaylistClient.parse("https://music.apple.com/ca/playlist/a-title/pl.123-abc")!!.path)
        assertNull(CatalogPlaylistClient.parse("https://open.spotify.com.example.org/playlist/abc"))
        assertNull(CatalogPlaylistClient.parse("https://www.youtube.com/playlist?list=abc"))
        assertThrows(IllegalArgumentException::class.java) { CatalogPlaylistClient.parse("https://open.spotify.com/track/abc") }
    }

    @Test fun `spotify pages preserve order duplicates and skip episodes nulls and local files`() {
        var calls = 0
        val client = CatalogPlaylistClient { _, token ->
            assertEquals("test-token", token)
            if (++calls == 1) json("""{"items":[{"item":{"type":"track","name":"First","artists":[{"name":"Artist"}]}},{"item":null},{"item":{"type":"episode"}},{"track":{"type":"track","is_local":true}}],"next":"https://api.spotify.com/v1/page2"}""")
            else json("""{"items":[{"track":{"type":"track","name":"First","artists":[{"name":"Artist"}]}}],"next":null}""")
        }
        val songs = client.load(CatalogPlaylistClient.parse("spotify:playlist:abc")!!, "test-token")
        assertEquals(2, calls)
        assertEquals(listOf("First", "First"), songs.map { it.title })
    }

    @Test fun `apple relationship tracks and subsequent pages are imported`() {
        var calls = 0
        val client = CatalogPlaylistClient { _, _ ->
            if (++calls == 1) json("""{"data":[{"relationships":{"tracks":{"data":[{"type":"songs","attributes":{"name":"One","artistName":"A"}}],"next":"/v1/catalog/ca/playlists/pl.abc/tracks?offset=1"}}}]}""")
            else json("""{"data":[{"type":"songs","attributes":{"name":"Two","artistName":"B"}}]}""")
        }
        assertEquals(listOf("One", "Two"), client.load(CatalogPlaylistClient.parse("https://music.apple.com/ca/playlist/name/pl.abc")!!, "token").map { it.title })
    }

    @Test fun `pagination never sends credentials to a different host or repeats a page`() {
        for (next in listOf("https://evil.example/steal", "https://api.spotify.com/v1/playlists/abc/items?limit=50")) {
            var calls = 0
            val client = CatalogPlaylistClient { _, _ -> calls++; json("""{"items":[],"next":"$next"}""") }
            assertThrows(IllegalArgumentException::class.java) { client.load(CatalogPlaylistClient.parse("spotify:playlist:abc")!!, "token") }
            assertEquals(1, calls)
        }
    }

    @Test fun `missing credentials fail before any network request`() {
        val client = CatalogPlaylistClient { _, _ -> error("must not request") }
        assertThrows(IllegalArgumentException::class.java) { client.load(CatalogPlaylistClient.parse("spotify:playlist:abc")!!, "") }
    }
}
