package ca.saboor.discs.playlist

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicPlaylistClientTest {
    private val spotify = CatalogPlaylistClient.parse("spotify:playlist:abc")!!
    private val apple = CatalogPlaylistClient.parse("https://music.apple.com/us/playlist/name/pl.abc")!!
    private fun spotifyHtml(rows: String) = """<script type="application/json" id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","trackList":[$rows]}}}}}}</script>"""
    private val row = """{"uri":"spotify:track:1","title":"One & Two","subtitle":"Artist"}"""

    @Test fun `spotify public import requests embed without credentials and preserves duplicate songs`() {
        val client = PublicPlaylistClient { uri ->
            assertEquals("https://open.spotify.com/embed/playlist/abc", uri.toString())
            spotifyHtml("$row,$row,{\"uri\":\"spotify:episode:2\",\"title\":\"Podcast\",\"subtitle\":\"Host\"}")
        }
        val result = client.load(spotify)
        assertEquals(listOf("One & Two", "One & Two"), result.songs.map { it.title })
        assertNull(result.notice)
    }

    @Test fun `spotify public preview limit is disclosed`() {
        val result = PublicPlaylistClient { spotifyHtml(List(100) { row }.joinToString(",")) }.load(spotify)
        assertEquals(100, result.songs.size)
        assertTrue(result.notice!!.contains("not be imported in full"))
    }

    @Test fun `apple serialized song rows exclude recommendations and disclose partial pages`() {
        val html = """<script id=serialized-server-data type="application/json">{"data":[{"data":{"sections":[
          {"itemKind":"containerDetailHeaderLockup","items":[{"trackCount":2}]},
          {"itemKind":"trackLockup","items":[{"title":"Song","contentDescriptor":{"kind":"song"},"subtitleLinks":[{"title":"Artist"}]}]},
          {"itemKind":"bubbleLockup","items":[{"title":"Recommendation"}]}
        ]}}]}</script>"""
        val result = PublicPlaylistClient { uri ->
            assertEquals("https://music.apple.com/us/playlist/pl.abc", uri.toString())
            html
        }.load(apple)
        assertEquals(listOf(CatalogPlaylistClient.Song("Song", "Artist")), result.songs)
        assertTrue(result.notice!!.contains("1 of 2"))
    }

    @Test fun `private error and changed pages fail instead of returning empty imports`() {
        for (html in listOf("<html>Not available</html>", "<script id='__NEXT_DATA__'>{\"props\":{\"pageProps\":{\"status\":500}}}</script>")) {
            assertThrows(IllegalStateException::class.java) { PublicPlaylistClient { html }.load(spotify) }
        }
    }
    @Test fun `single spotify tracks resolve title and artists from embed`() {
        val client = PublicPlaylistClient { uri ->
            assertEquals("https://open.spotify.com/embed/track/abc", uri.toString())
            """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Song","artists":[{"name":"Artist"}]}}}}}}</script>"""
        }
        assertEquals(CatalogPlaylistClient.Song("Song", "Artist"), client.loadSong("spotify:track:abc"))
        assertEquals(CatalogPlaylistClient.Song("Song", "Artist"), client.loadSong("https://open.spotify.com/intl-en/track/abc?si=share"))
    }

    @Test fun `apple album song link selects requested track rather than first song`() {
        val client = PublicPlaylistClient {
            """<script id="serialized-server-data">{"items":[
              {"title":"Other","contentDescriptor":{"kind":"song","id":"1"},"subtitleLinks":[{"title":"Artist"}]},
              {"title":"Selected","contentDescriptor":{"kind":"song","id":"2"},"subtitleLinks":[{"title":"Artist"}]}
            ]}</script>"""
        }
        assertEquals(CatalogPlaylistClient.Song("Selected", "Artist"), client.loadSong("https://music.apple.com/us/album/name/123?i=2"))
        assertThrows(IllegalStateException::class.java) { client.loadSong("https://music.apple.com/us/song/name/3") }
    }

    @Test fun `song resolver passes other sources through and rejects unsafe catalog links`() {
        val client = PublicPlaylistClient { error("Must not fetch") }
        assertNull(client.loadSong("https://example.com/song.mp3"))
        assertThrows(IllegalArgumentException::class.java) { client.loadSong("http://open.spotify.com/track/abc") }
        assertThrows(IllegalArgumentException::class.java) { client.loadSong("https://music.apple.com/us/album/name/123") }
        assertThrows(IllegalArgumentException::class.java) { client.loadSong("spotify:track:../abc") }
    }
}
