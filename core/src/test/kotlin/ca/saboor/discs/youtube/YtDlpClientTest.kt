package ca.saboor.discs.youtube

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class YtDlpClientTest {
    @TempDir lateinit var directory: Path
    private val json = """{"id":"abcdefghijk","title":"Song","uploader":"Artist","duration":12.5,"url":"https://example.com/audio.webm","http_headers":{"User-Agent":"test-agent"},"format":"251 opus"}"""
    private fun script(body: String): String {
        assumeTrue(java.io.File("/bin/sh").canExecute())
        val script = directory.resolve("fake-ytdlp").toFile()
        script.writeText("#!/bin/sh\n$body\n")
        assertTrue(script.setExecutable(true))
        return script.path
    }

    @Test fun `extracts metadata and headers once for simultaneous clones then refreshes expired URL`() {
        val count = directory.resolve("count").toFile()
        val executable = script("printf 'call\\n' >> '${count.path}'\nsleep 0.1\ncat <<'JSON'\n$json\nJSON")
        var now = 0L
        val client = YtDlpClient(executable, clock = { now })
        val pool = Executors.newFixedThreadPool(4)
        try {
            val requests = (1..4).map { pool.submit<YtDlpClient.Media> { client.audio("abcdefghijk") } }
            requests.forEach {
                val media = it.get(5, TimeUnit.SECONDS)
                assertEquals("Song", media.title)
                assertEquals(12500, media.durationMs)
                assertEquals("test-agent", media.headers["User-Agent"])
                assertEquals("https://www.youtube.com/watch?v=abcdefghijk", media.watchUrl)
            }
            assertEquals(1, count.readLines().size)
            now = 300_001
            client.audio("abcdefghijk")
            assertEquals(2, count.readLines().size)
            client.video("abcdefghijk", 1080)
            assertEquals(3, count.readLines().size)
        } finally { pool.shutdownNow(); client.close() }
    }

    @Test fun `explicit config and queries are passed as literal arguments`() {
        val args = directory.resolve("args").toFile()
        val executable = script("printf '%s\\n' \"\$@\" > '${args.path}'\ncat <<'JSON'\n{\"title\":\"Results\",\"entries\":[]}\nJSON")
        YtDlpClient(executable, "/private/config with spaces").use { client ->
            client.playlist("ytsearch:--exec touch /tmp/should-not-exist")
            val passed = args.readLines()
            assertEquals("--ignore-config", passed.first())
            assertEquals("/private/config with spaces", passed[passed.indexOf("--config-locations") + 1])
            assertEquals("--", passed[passed.lastIndex - 1])
            assertEquals("ytsearch10:--exec touch /tmp/should-not-exist", passed.last())
        }
    }

    @Test fun `timeout kills extractor and failure does not poison the cache`() {
        val executable = script("sleep 10")
        val started = System.nanoTime()
        YtDlpClient(executable, timeoutSeconds = 1).use { client ->
            val error = assertThrows(IllegalStateException::class.java) { client.audio("abcdefghijk") }
            assertTrue(error.message!!.contains("timed out"))
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
            script("cat <<'JSON'\n$json\nJSON")
            assertEquals("Song", client.audio("abcdefghijk").title)
        }
    }

    @Test fun `diagnostics are bounded and secrets never escape extractor failures`() {
        val executable = script("head -c 131072 /dev/zero >&2\nprintf 'Sign in to confirm you are not a bot refresh_token=SECRET https://private.example/?secret=SECRET' >&2\nexit 1")
        YtDlpClient(executable).use { client ->
            val error = assertThrows(IllegalStateException::class.java) { client.audio("abcdefghijk") }
            assertFalse(error.message!!.contains("SECRET"))
            assertFalse(error.message!!.contains("private.example"))
        }
        script("printf 'ERROR: Sign in to confirm you are not a bot token=SECRET' >&2\nexit 1")
        YtDlpClient(executable).use { client ->
            val error = assertThrows(IllegalStateException::class.java) { client.audio("abcdefghijk") }
            assertTrue(error.message!!.contains("PO-token"))
            assertFalse(error.message!!.contains("SECRET"))
        }
    }

    @Test fun `flat playlists ignore unavailable entries and preserve canonical video IDs`() {
        val executable = script("cat <<'JSON'\n{\"title\":\"Playlist\",\"entries\":[$json,null,{\"id\":\"invalid\"}]}\nJSON")
        YtDlpClient(executable).use { client ->
            val (title, entries) = client.playlist("https://www.youtube.com/playlist?list=PLtest")
            assertEquals("Playlist", title)
            assertEquals(1, entries.size)
            assertNull(entries.single().url)
        }
        assertFalse(YtDlpAudioSourceManager.accepts("https://youtube.com.evil.example/watch?v=abcdefghijk"))
        assertTrue(YtDlpAudioSourceManager.accepts("https://music.youtube.com/watch?v=abcdefghijk"))
        assertTrue(YtDlpAudioSourceManager.accepts("abcdefghijk"))
        assertThrows(IllegalArgumentException::class.java) {
            YtDlpClient.parseMedia(JsonParser.parseString(json.replace("https://example.com/audio.webm", "file:///etc/passwd")).asJsonObject)
        }
    }
}
