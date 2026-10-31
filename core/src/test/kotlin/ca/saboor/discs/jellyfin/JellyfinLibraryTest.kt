package ca.saboor.discs.jellyfin

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
    @Test fun `readers keep the committed state while persistence is blocked and failed writes never publish`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var blockWrites = false
        val library = JellyfinLibrary(folder.resolve("blocked.json")) { _, _ ->
            if (blockWrites) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw java.io.IOException("Disk unavailable")
            }
        }
        val owner = UUID.randomUUID()
        val original = library.connect(owner, session())
        val disc = library.grant(original, song)
        blockWrites = true
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit { assertThrows(java.io.IOException::class.java) { library.disconnect(owner) } }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val reader = executor.submit<Boolean> {
                library.connection(owner) === original && library.resolve(disc).first === original
            }
            assertTrue(reader.get(2, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            writer.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertSame(original, library.connection(owner))
            assertSame(original, library.resolve(disc).first)
        } finally { release.countDown(); executor.shutdownNow() }
    }

}
