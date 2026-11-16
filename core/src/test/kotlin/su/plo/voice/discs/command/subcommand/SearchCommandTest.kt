package su.plo.voice.discs.command.subcommand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchCommandTest {
    @Test
    fun `searchIdentifier defaults to youtube and supports soundcloud prefixes`() {
        assertEquals("ytsearch:daft punk", SearchCommand.searchIdentifier("daft punk"))
        assertEquals("ytsearch:daft punk", SearchCommand.searchIdentifier("ytsearch:daft punk"))
        assertEquals("ytmsearch:daft punk", SearchCommand.searchIdentifier("ytmsearch:daft punk"))
        assertEquals("scsearch:daft punk", SearchCommand.searchIdentifier("scsearch:daft punk"))
        assertEquals("scsearch:daft punk", SearchCommand.searchIdentifier("soundcloud:daft punk"))
        assertEquals("scsearch:daft punk", SearchCommand.searchIdentifier("sc:daft punk"))
        assertEquals("scsearch:daft punk", SearchCommand.searchIdentifier("soundcloud daft punk"))
        assertEquals("scsearch:daft punk", SearchCommand.searchIdentifier("SC daft punk"))
    }
}
