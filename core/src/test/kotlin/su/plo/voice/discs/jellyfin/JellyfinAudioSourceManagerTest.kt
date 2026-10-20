package su.plo.voice.discs.jellyfin

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioReference
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.UUID

class JellyfinAudioSourceManagerTest {
    @TempDir lateinit var folder: Path
    @Test fun `registered disc loads authenticated audio without putting tokens in the track URL`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        var requestedPath: String? = null
        val wav = ByteBuffer.allocate(44 + 16000).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16000); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(16000)
        }.array()
        server.createContext("/jellyfin/Audio/") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestedPath = exchange.requestURI.toString()
            exchange.responseHeaders.set("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }; exchange.close()
        }
        server.start()
        val playerManager = DefaultAudioPlayerManager()
        val library = JellyfinLibrary(folder.resolve("jellyfin.json"))
        val source = JellyfinAudioSourceManager(library)
        try {
            val session = JellyfinClient.Session("http://127.0.0.1:${server.address.port}/jellyfin/",
                "0123456789abcdef0123456789abcdef", "private-token", "device")
            val connection = library.connect(UUID.randomUUID(), session)
            val ref = library.grant(connection, JellyfinClient.Media("abcdef0123456789abcdef0123456789", "Song", "", "", 1000))
            val track = source.loadItem(playerManager, AudioReference(ref, null)) as AudioTrack
            assertTrue(authorization!!.contains("Token=\"private-token\""))
            assertTrue(requestedPath!!.endsWith("/stream?static=true"))
            assertFalse(track.info.uri.contains("private-token"))
            assertFalse(track.identifier.contains("private-token"))
            assertNotNull(track.makeClone())
            assertNull(source.loadItem(playerManager, AudioReference("https://example.com/", null)))
            library.disconnect(connection.owner)
            assertThrows(JellyfinException::class.java) { source.loadItem(playerManager, AudioReference(ref, null)) }
        } finally { source.shutdown(); playerManager.shutdown(); server.stop(0) }
    }
}
