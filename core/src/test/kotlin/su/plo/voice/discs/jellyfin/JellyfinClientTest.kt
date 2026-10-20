package su.plo.voice.discs.jellyfin

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class JellyfinClientTest {
    @Test fun `server URL preserves reverse proxy paths and rejects embedded credentials`() {
        assertEquals("https://example.com/jellyfin/", JellyfinClient.normalizeUrl("https://example.com/jellyfin"))
        for (url in listOf("file:///tmp/media", "ftp://example.com", "https://user:pass@example.com", "https://example.com?api_key=secret", "https://example.com/#token"))
            assertThrows(JellyfinException::class.java) { JellyfinClient.normalizeUrl(url) }
    }
    @Test fun `authentication and paginated search use headers and never URL credentials`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Triple<String, String, String>>()
        server.createContext("/jellyfin/") { exchange ->
            val path = exchange.requestURI.toString()
            val body = exchange.requestBody.bufferedReader().readText()
            requests += Triple(path, exchange.requestHeaders.getFirst("Authorization"), body)
            val response = when {
                path.contains("AuthenticateByName") -> """{"AccessToken":"test-token","User":{"Id":"0123456789abcdef0123456789abcdef"}}"""
                path.contains("Items?") -> """{"Items":[{"Id":"abcdef0123456789abcdef0123456789","Name":"Song","Artists":["Artist"],"Album":"Album","RunTimeTicks":900000000}],"TotalRecordCount":100}"""
                else -> ""
            }.toByteArray()
            if (response.isEmpty()) exchange.sendResponseHeaders(204, -1)
            else { exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response) }
            exchange.close()
        }
        server.start()
        try {
            val client = JellyfinClient()
            val session = client.login("http://127.0.0.1:${server.address.port}/jellyfin", "listener", "a secret", "test-device")
            val page = client.browse(session, 1, "artist & song")
            assertEquals(100, page.total)
            assertEquals("Artist - Song", page.items.single().label)
            assertEquals(90000L, page.items.single().durationMs)
            assertTrue(requests[0].third.contains("a secret"))
            assertFalse(requests[0].second.contains("Token="))
            assertTrue(requests[1].first.contains("startIndex=45"))
            assertTrue(requests[1].first.contains("searchTerm=artist+%26+song"))
            assertTrue(requests[1].second.contains("Token=\"test-token\""))
            assertTrue(requests.none { it.first.contains("test-token") || it.first.contains("secret") })
            assertFalse(client.streamUrl(session, page.items.single().id).contains("test-token"))
            client.logout(session)
            assertTrue(requests.last().first.endsWith("Sessions/Logout"))
        } finally { server.stop(0) }
    }
    @Test fun `redirects never receive authentication headers`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var destinationCalled = false
        server.createContext("/Users/AuthenticateByName") { exchange ->
            exchange.responseHeaders.set("Location", "/destination")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/destination") { exchange -> destinationCalled = true; exchange.sendResponseHeaders(401, -1); exchange.close() }
        server.start()
        try {
            assertThrows(JellyfinException::class.java) { JellyfinClient().login("http://127.0.0.1:${server.address.port}", "user", "password", "device") }
            assertFalse(destinationCalled)
        } finally { server.stop(0) }
    }
}
