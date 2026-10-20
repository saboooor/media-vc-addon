package su.plo.voice.discs.jellyfin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID

class JellyfinLibraryTest {
    @TempDir lateinit var folder: Path
    private fun session(url: String = "https://media.example/jellyfin/", token: String = "private-token") =
        JellyfinClient.Session(url, "0123456789abcdef0123456789abcdef", token, UUID.randomUUID().toString())
    private val song = JellyfinClient.Media("abcdef0123456789abcdef0123456789", "Song", "Artist", "Album", 90000)

    @Test fun `disc references contain no credentials and selected media survives reload`() {
        val file = folder.resolve("jellyfin.json")
        val library = JellyfinLibrary(file)
        val owner = UUID.randomUUID()
        val connection = library.connect(owner, session())
        val disc = library.grant(connection, song)
        assertNotNull(JellyfinLibrary.parseReference(disc))
        assertFalse(disc.contains("private-token"))
        assertFalse(disc.contains("media.example"))
        val (restored, grant) = JellyfinLibrary(file).resolve(disc)
        assertEquals(owner, restored.owner)
        assertEquals("private-token", restored.session.token)
        assertEquals(song.id, grant.mediaId)
        assertFalse(Files.readString(file).contains("password"))
        assertFalse(restored.toString().contains("private-token"))
        assertFalse(restored.session.toString().contains("private-token"))
        assertThrows(JellyfinException::class.java) { library.resolve("jellyfin:${UUID.randomUUID()}") }
    }
    @Test fun `disconnect removes token and reconnecting the same account restores existing discs`() {
        val file = folder.resolve("jellyfin.json")
        val library = JellyfinLibrary(file)
        val owner = UUID.randomUUID()
        val first = library.connect(owner, session())
        val disc = library.grant(first, song)
        library.disconnect(owner)
        assertFalse(Files.readString(file).contains("private-token"))
        assertThrows(JellyfinException::class.java) { library.resolve(disc) }
        library.connect(owner, session(token = "new-token"))
        assertEquals("new-token", library.resolve(disc).first.session.token)
        library.connect(owner, session(url = "https://different.example/"))
        assertThrows(JellyfinException::class.java) { library.resolve(disc) }
        assertThrows(IllegalStateException::class.java) { library.grant(first, song) }
    }
    @Test fun `players cannot browse or grant media through another players connection`() {
        val library = JellyfinLibrary(folder.resolve("jellyfin.json"))
        val alice = library.connect(UUID.randomUUID(), session())
        val bob = library.connect(UUID.randomUUID(), session())
        assertNotEquals(alice.id, bob.id)
        val disc = library.grant(alice, song)
        assertEquals(alice.owner, library.resolve(disc).first.owner)
        library.disconnect(bob.owner)
        assertEquals(alice.owner, library.resolve(disc).first.owner)
        library.revoke(disc)
        assertThrows(JellyfinException::class.java) { library.resolve(disc) }
    }
}
