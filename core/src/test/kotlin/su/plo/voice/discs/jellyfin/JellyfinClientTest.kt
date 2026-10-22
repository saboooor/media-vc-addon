package su.plo.voice.discs.jellyfin

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class JellyfinClientTest {
    @Test fun `library lists shows instead of episodes and episodes are scoped ordered and paginated`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seriesId = "0123456789abcdef0123456789abcdef"
        val paths = mutableListOf<String>()
        val episodes = listOf(
            """{"Id":"00000000000000000000000000000001","Name":"Special","Type":"Episode","ParentIndexNumber":0,"IndexNumber":1}""",
            """{"Id":"00000000000000000000000000000002","Name":"Zulu","Type":"Episode","ParentIndexNumber":1,"IndexNumber":2}""",
            """{"Id":"00000000000000000000000000000003","Name":"Alpha","Type":"Episode","ParentIndexNumber":1,"IndexNumber":10}""",
            """{"Id":"00000000000000000000000000000004","Name":"New season","Type":"Episode","ParentIndexNumber":2,"IndexNumber":1}"""
        )
        server.createContext("/jellyfin/Items") { exchange ->
            val uri = exchange.requestURI
            paths += uri.toString()
            val query = uri.rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertTrue(exchange.requestHeaders.getFirst("Authorization").contains("test-token"))
            val response = if (query["parentId"] == null) {
                assertEquals("Audio,AudioBook,Movie,Series,MusicVideo,Video", query["includeItemTypes"])
                """{"Items":[{"Id":"$seriesId","Name":"A show","Type":"Series","RunTimeTicks":null}],"TotalRecordCount":1}"""
            } else {
                assertEquals(seriesId, query["parentId"])
                assertEquals("Episode", query["includeItemTypes"])
                assertEquals("true", query["recursive"])
                assertEquals("false", query["isMissing"])
                assertEquals("ParentIndexNumber,IndexNumber,SortName", query["sortBy"])
                assertEquals("Ascending", query["sortOrder"])
                assertEquals("title & name", query["searchTerm"])
                val offset = query.getValue("startIndex").toInt()
                val limit = query.getValue("limit").toInt()
                """{"Items":[${episodes.drop(offset).take(limit).joinToString(",")}],"TotalRecordCount":4}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val session = JellyfinClient.Session("http://127.0.0.1:${server.address.port}/jellyfin/", seriesId, "test-token", "device")
            val client = JellyfinClient()
            val show = client.browse(session, 0).items.single()
            assertTrue(show.isSeries)
            assertEquals("A show", show.browserLabel)
            val first = client.episodes(session, show.id, 0, "title & name", 2)
            val second = client.episodes(session, show.id, 1, "title & name", 2)
            assertEquals(4, first.total)
            assertEquals(listOf("S00E01 · Special", "S01E02 · Zulu", "S01E10 · Alpha", "S02E01 · New season"),
                (first.items + second.items).map { it.browserLabel })
            assertTrue(second.items.none { it.isSeries })
            assertEquals("", client.episodes(session, show.id, 2, "title & name", 2).items.joinToString())
            assertTrue(paths.none { "test-token" in it })
            assertThrows(IllegalArgumentException::class.java) { client.episodes(session, "bad/path", 0) }
            assertThrows(IllegalArgumentException::class.java) { client.episodes(session, show.id, -1) }
        } finally { server.stop(0) }
    }
    @Test fun `episode labels handle missing numbering without hiding titles`() {
        assertEquals("S?E? · Pilot", JellyfinClient.Media("id", "Pilot", "", "", 0, "Episode").browserLabel)
        assertEquals("Artist - Song", JellyfinClient.Media("id", "Song", "Artist", "", 1000).browserLabel)
    }
    @Test fun `subtitle download uses actual source ID converted SRT and header authentication`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        server.createContext("/proxy/") { exchange ->
            paths += exchange.requestURI.toString()
            assertTrue(exchange.requestHeaders.getFirst("Authorization").contains("test-token"))
            val response = (if (exchange.requestURI.path.contains("PlaybackInfo"))
                """{"MediaSources":[{"Id":"different-source","MediaStreams":[{"Type":"Subtitle","Index":3,"DisplayTitle":"English SDH","Language":"eng","Codec":"subrip","IsTextSubtitleStream":true,"DeliveryUrl":null},{"Type":"Subtitle","Index":4,"Codec":"pgssub","IsTextSubtitleStream":false}]}]}"""
                else "1\n00:00:01,000 --> 00:00:02,000\nHello").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val session = JellyfinClient.Session("http://127.0.0.1:${server.address.port}/proxy/", "0123456789abcdef0123456789abcdef", "test-token", "device")
            val client = JellyfinClient()
            val item = "abcdef0123456789abcdef0123456789"
            val tracks = client.getSubtitleTracks(session, item)
            assertEquals(1, tracks.size)
            assertEquals("different-source", tracks.single().mediaSourceId)
            assertTrue(client.downloadSubtitle(session, item, tracks.single())!!.contains("Hello"))
            assertEquals("/proxy/Videos/$item/different-source/Subtitles/3/0/Stream.srt?copyTimestamps=true", paths.last())
            assertTrue(paths.none { "test-token" in it })
        } finally { server.stop(0) }
    }
    @Test fun `unknown artists do not appear in Jellyfin labels`() {
        assertEquals("Movie", JellyfinClient.Media("id", "Movie", "Unknown Artist", "", 1000).label)
        assertEquals("Artist - Song", JellyfinClient.Media("id", "Song", "Artist", "", 1000).label)
    }
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
            assertTrue(requests[1].first.contains("includeItemTypes=Audio,AudioBook,Movie,Series,MusicVideo,Video"))
            val firstSeek = client.streamUrl(session, page.items.single().id, "Movie", 50_000)
            val secondSeek = client.streamUrl(session, page.items.single().id, "Movie", 50_000)
            val firstSession = firstSeek.substringAfter("playSessionId=")
            val secondSession = secondSeek.substringAfter("playSessionId=")
            assertDoesNotThrow { java.util.UUID.fromString(firstSession) }
            assertNotEquals(firstSession, secondSession)
            val videoUrl = client.streamUrl(session, page.items.single().id, "Movie")
            assertEquals("${session.baseUrl}Videos/${page.items.single().id}/stream?static=true",
                client.videoStreamUrl(session, page.items.single().id))
            assertTrue(videoUrl.contains("container=mp3"))
            assertTrue(videoUrl.contains("static=false"))
            assertTrue(client.streamUrl(session, page.items.single().id, "Movie", 50_000).contains("startTimeTicks=500000000"))
            assertFalse(videoUrl.contains("test-token"))
            assertTrue(client.streamUrl(session, page.items.single().id).endsWith("static=true"))
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

