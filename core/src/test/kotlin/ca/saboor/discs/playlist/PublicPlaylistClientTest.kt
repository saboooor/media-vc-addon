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
}
