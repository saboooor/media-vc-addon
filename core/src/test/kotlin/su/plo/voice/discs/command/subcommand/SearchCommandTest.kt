package su.plo.voice.discs.command.subcommand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SearchCommandTest {

    @Test
    fun `parses explicit youtube soundcloud and jellyfin search targets`() {
        assertEquals(
            SearchCommand.SearchTarget.Youtube("britney spears circus"),
            SearchCommand.parseSearchArgs(arrayOf("search", "youtube", "britney", "spears", "circus")),
        )
        assertEquals(
            SearchCommand.SearchTarget.Youtube("circus"),
            SearchCommand.parseSearchArgs(arrayOf("search", "yt", "circus")),
        )
        assertEquals(
            SearchCommand.SearchTarget.SoundCloud("tinyteaze circus"),
            SearchCommand.parseSearchArgs(arrayOf("search", "soundcloud", "tinyteaze", "circus")),
        )
        assertEquals(
            SearchCommand.SearchTarget.SoundCloud("tinyteaze"),
            SearchCommand.parseSearchArgs(arrayOf("search", "sc", "tinyteaze")),
        )
        assertEquals(
            SearchCommand.SearchTarget.Jellyfin("daft punk"),
            SearchCommand.parseSearchArgs(arrayOf("search", "jellyfin", "daft", "punk")),
        )
        assertEquals(
            SearchCommand.SearchTarget.Jellyfin("daft punk"),
            SearchCommand.parseSearchArgs(arrayOf("search", "jf", "daft", "punk")),
        )

        assertEquals(
            "ytsearch:britney spears",
            SearchCommand.searchIdentifier(SearchCommand.SearchTarget.Youtube("britney spears")),
        )
        assertEquals(
            "scsearch:britney spears",
            SearchCommand.searchIdentifier(SearchCommand.SearchTarget.SoundCloud("britney spears")),
        )
    }

    @Test
    fun `rejects missing or unknown search source or empty query`() {
        assertNull(SearchCommand.parseSearchArgs(arrayOf("search")))
        assertNull(SearchCommand.parseSearchArgs(arrayOf("search", "youtube")))
        assertNull(SearchCommand.parseSearchArgs(arrayOf("search", "youtube", "   ")))
        assertNull(SearchCommand.parseSearchArgs(arrayOf("search", "britney", "spears")))
    }
}
