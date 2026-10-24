package ca.saboor.discs.jellyfin

import ca.saboor.discs.PlaybackControls
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioReference

class JellyfinAudioTrackSelectionTest {
    @TempDir lateinit var directory: Path

    @Test fun `switching language uses selected stream and preserves position pause volume and seeks`() {
        val wav = ByteBuffer.allocate(44 + 16000).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16000); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(16000)
        }.array()
        val progressiveMkv = java.util.Base64.getDecoder().decode(
            "GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcB/////////xFNm3Sxv4T4w48yTbuLU6uEFUmpZlOsgaFN" +
                "u4tTq4QWVK5rU6yB5E27jFOrhBJUw2dTrIIBR+wBAAAAAAAAYgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZr6/hBE3hzIq17GDD0JATYCM" +
                "TGF2ZjYzLjEuMTAxV0GMTGF2ZjYzLjEuMTAxc6SQU2Qom6zwegNLXG5gmb5BdRZUrmvev4Rb5wx8rgEAAAAAAABP14EBc8WIvUiYPGGJ" +
                "pj2cgQAitZyDdW5kiIEAhoVBX0FBQ1aqhAehIACDgQIj44OEB6EgAOGRn4EBtYhAv0AAAAAAAGJkgSBjooUViFblABJUw2fZv4SGJm8W" +
                "c3OfY8CAZ8iZRaOHRU5DT0RFUkSHjExhdmY2My4xLjEwMXNzrmPAi2PFiL1ImDxhiaY9Z8idRaOHRU5DT0RFUkSHkExhdmM2My4xLjEw" +
                "MSBhYWMfQ7Z1Q6K/hFZIHwDngQCjQjyBAACA3ABMYXZjNjMuMS4xMDEAAjRoXGFIywroqo76VqVKEyVJtJJiJP//Y8A4jhXQu+/5/BuK" +
                "Z2BageRvqPkX/33LdPp3en1jx3SXkstg+t+zbdn4EsC+tfK8rTqChxce/kv/VdA4QPM32GWxV2OXj/ncGNMjOOLqVljFwWHU8yfZRKau" +
                "7advViGWkE8fqiVWTMk2dm0JYJCORKui4Vcrl5n4m6hzIeugzKLnbj2Xwax5K/G4NLg91d49X9E9t4juLzDwLvXHW6eo9q7Sq3LW0KOx" +
                "zkvIum5FwrOGRtQQanc5/r/u12Xuaro9/S+nY52g1XMpYuf4+mzVZm93o1zn4a6JZZkq1r7HPZnZm9oCmbfDfeStILxcm3RjRMFYImfD" +
                "HClEgvPox4XyIJiiS4Y4SokEQ6M+GOQlMgeL4evtHSaX4dFtEvD1yG0I+Hi2jYeHrkNoR8PiQ6TS/D0No0Hw9ch0iPh4tAaD/Dh5DaEf" +
                "D46A6SMfYQRj8SDEY9yDA/EgwjHuAMRh9wwjHuAMRh7gBEfOAMRh7gDEc9wxGHuAMRh+RiiHrEMRh7gBEPWIMWh6wBhO2IIdE8IEO87w" +
                "hTxZOX0kn3TnxPnPcCfVcOQuQiGc6yQ8Ac/Icd6RZnMSe50xPc9dJ+Aeck93xYlwS2Q5Jw8hwbqZDu/KyGQzpHMZgnz7lhPhfQCfZOMk" +
                "4TSUnWkOodBIcL5OQ6ZwcnBuE37cn1bphPg/CyfMdqQwWsIY3gBDq/UyHBNaQ4jnuKBBV6FBUYEAgAABGDQotmozFKQAAAAkRIiSBNit" +
                "qsRddznKX1NZtqsUuRznaayJZtqyqGeeO9VxpjJdWxaHN3PuxcVVLC427qwrG8w/ce8Y93V/qx8D0q486lIqYRaitFkqVslRERbNIzYV" +
                "Yl8mJCGSED0p39u//v9G04b1b4LbjeR6rxl9TYrarE5ecTYVLqSno4pLIzqkBk2bFCsWqk4ZsqKLDyYDTjjMVeGAtjjMleGDnTjMj4YO" +
                "bYzITyODNmz++X4b99GT4d95vhv30P/CfcvjNn9z/BveHJmd95vhv30P/CfcvhvPof+IPeHJmd9z/BveHJ8J9y+G8+h/4T7w/GbP7n+D" +
                "e8OTM77zfDefQ/8J9y+M2f3f+De8OTM77n+De+h/4T7l8N59D/xB7w/GZ33P8G94cmZ33m+G/fQ/8J9y+G8+h/4N7w5MzvuPg2fQ/8D3LJybgTI="
        )
        val id = "0123456789abcdef0123456789abcdef"
        val paths = ConcurrentLinkedQueue<String>()
        val headers = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.toString())
            headers.add(exchange.requestHeaders.getFirst("Authorization") ?: "missing")
            val isPlaybackInfo = exchange.requestURI.path.contains("PlaybackInfo")
            val isVideoMkv = exchange.requestURI.path.startsWith("/Videos/")
            val bytes = when {
                isPlaybackInfo -> """{"MediaSources":[{"Id":"source 1","MediaStreams":[
                    {"Type":"Video","Index":0},
                    {"Type":"Audio","Index":1,"DisplayTitle":"Japanese stereo","Language":"jpn","IsDefault":true},
                    {"Type":"Subtitle","Index":2},
                    {"Type":"Audio","Index":4,"DisplayTitle":"English commentary","Language":"eng"}]}]}""".toByteArray()
                isVideoMkv -> progressiveMkv
                else -> wav
            }
            exchange.responseHeaders.set("Content-Type", when {
                isPlaybackInfo -> "application/json"
                isVideoMkv -> "video/x-matroska"
                else -> "audio/wav"
            })
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
        val manager = DefaultAudioPlayerManager()
        val library = JellyfinLibrary(directory.resolve("library.json"))
        val source = JellyfinAudioSourceManager(library)
        val player = manager.createPlayer()
        val controls = PlaybackControls(initialVolume = 65)
        try {
            val session = JellyfinClient.Session("http://127.0.0.1:${server.address.port}/", id, "private-token", "device")
            val connection = library.connect(UUID.randomUUID(), session)
            val reference = library.grant(connection, JellyfinClient.Media(id, "Movie", "", "", 120_000, "Movie"))
            val track = source.loadItem(manager, AudioReference(reference, null)) as JellyfinTranscodedTrack
            player.playTrack(track); controls.attach(player); controls.togglePause()
            val tracks = controls.loadJellyfinAudioTracks()
            assertEquals(listOf(1, 4), tracks.map { it.index })
            assertEquals("eng", tracks.last().language)
            controls.seekTo(50_000)
            assertTrue(controls.selectJellyfinAudioTrack(tracks.last()))
            assertEquals(4, controls.selectedJellyfinAudioTrack)
            assertEquals(50_000L, controls.position)
            assertEquals(65, player.volume)
            assertTrue(player.isPaused)
            val clone = player.playingTrack.makeClone() as JellyfinTranscodedTrack
            assertEquals(4, clone.selectedAudioTrack!!.index)
            assertEquals(4, clone.restartAt(60_000).selectedAudioTrack!!.index)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (paths.none { it.startsWith("/Videos/") } && System.nanoTime() < deadline) Thread.sleep(20)
            val selectedPath = paths.firstOrNull { it.startsWith("/Videos/") }
            assertNotNull(selectedPath, "Should have received /Videos/ request within timeout")
            assertTrue(selectedPath!!.contains("audioStreamIndex=4"))
            assertTrue(selectedPath.contains("mediaSourceId=source+1"))
            assertTrue(selectedPath.contains("startTimeTicks=500000000"))
            assertFalse(selectedPath.contains("private-token"))
            assertTrue(headers.all { it.contains("private-token") })
            controls.seekTo(60_000)
            assertEquals(4, controls.selectedJellyfinAudioTrack)
            assertTrue(controls.selectJellyfinAudioTrack(null))
            assertNull(controls.selectedJellyfinAudioTrack)
            assertEquals(60_000L, controls.position)
        } finally {
            controls.detach(player)
            player.destroy()
            source.shutdown()
            manager.shutdown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
