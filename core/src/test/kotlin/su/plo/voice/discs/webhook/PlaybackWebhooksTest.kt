package su.plo.voice.discs.webhook

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.plo.voice.discs.AddonConfig
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackWebhooksTest {
    @Test fun `provider OAuth logger forwards pairing prompts and ignores token responses`() {
        val prompts = mutableListOf<OauthPrompt>()
        val observer = OauthPromptLog { prompts += it }
        val name = su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager::class.java.packageName + ".http.YoutubeOauth2Handler"
        try {
            observer.start()
            val logger = org.apache.logging.log4j.LogManager.getLogger(name)
            logger.info("fetch device code response: access_token=do-not-forward")
            logger.info("OAUTH INTEGRATION: go to https://www.google.com/device and enter code ABCD-EFGH")
            assertEquals(listOf(OauthPrompt("https://www.google.com/device", "ABCD-EFGH")), prompts)
        } finally { observer.close() }
    }
    @Test fun `private endpoint receives deduplicated failure and one pairing prompt without mentions`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val delivered = CountDownLatch(2)
        server.createContext("/webhook") { exchange ->
            messages += exchange.requestBody.bufferedReader().readText()
            exchange.sendResponseHeaders(204, -1); exchange.close(); delivered.countDown()
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = AddonConfig.WebhookConfig().apply { url = "http://127.0.0.1:${server.address.port}/webhook" }
        val starts = AtomicInteger()
        val started = CountDownLatch(1)
        val webhook = PlaybackWebhooks(scope, { config }, { throw AssertionError(it) }, { starts.incrementAndGet(); started.countDown(); true })
        try {
            repeat(3) { webhook.failure("https://www.youtube.com/watch?v=abcdefghijk", "Sign in to confirm you're not a bot") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val prompt = OauthPrompt.parse("OAUTH INTEGRATION: To give youtube-source access to your account, go to https://www.google.com/device and enter code ABCD-EFGH")!!
            repeat(2) { webhook.oauthPrompt(prompt) }
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
            assertEquals(2, messages.size)
            assertTrue(messages.any { it.contains("ABCD-EFGH") && it.contains("https://www.google.com/device") })
            for (message in messages) assertEquals(0, JsonParser.parseString(message).asJsonObject.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size())
        } finally { webhook.close(); scope.cancel(); server.stop(0) }
    }
    @Test fun `disabled endpoint never starts automatic authorization`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val webhook = PlaybackWebhooks(scope, { AddonConfig.WebhookConfig() }, {}, { throw AssertionError("Must stay disabled") })
        try { webhook.failure("https://youtu.be/abcdefghijk", "Login required") }
        finally { webhook.close(); scope.cancel() }
    }
    @Test fun `error details redact credential values and private URLs`() {
        val text = PlaybackWebhooks.clean("OAuth failed refresh_token=secret123 access_token=access456 password='my password' Authorization: Bearer abc123 https://user:password@example.org/private?api_key=secret device_code=private-code")
        for (secret in listOf("secret123", "access456", "my password", "abc123", "user:password", "api_key", "private-code")) assertFalse(text.contains(secret), text)
        assertTrue(text.contains("[redacted]"))
        assertFalse(PlaybackWebhooks.isYoutube("https://youtube.com.evil.example/watch"))
        assertTrue(PlaybackWebhooks.isYoutube("https://youtu.be/abcdefghijk"))
    }
    @Test fun `only dedicated OAuth prompts can provide pairing details`() {
        assertNull(OauthPrompt.parse("fetch device code response: access_token=secret user_code=ABCD"))
        assertNull(OauthPrompt.parse("OAUTH INTEGRATION: go to https://evil.example/device and enter code ABCD-EFGH"))
        assertNull(OauthPrompt.parse("OAUTH INTEGRATION: go to https://www.google.com/device?token=secret and enter code ABCD-EFGH"))
        assertNotNull(OauthPrompt.parse("OAUTH INTEGRATION: go to https://www.google.com/device and enter code ABCD-EFGH"))
    }
    @Test fun `webhook redirects never receive the configured request`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val destination = AtomicInteger()
        val rejected = CountDownLatch(1)
        server.createContext("/redirect") { ex -> ex.responseHeaders.add("Location", "/other"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/other") { ex -> destination.incrementAndGet(); ex.sendResponseHeaders(204, -1); ex.close() }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = AddonConfig.WebhookConfig().apply { url = "http://127.0.0.1:${server.address.port}/redirect"; autoYoutubeOauth = false }
        val webhook = PlaybackWebhooks(scope, { config }, { rejected.countDown() }, { false })
        try {
            webhook.failure("https://youtu.be/abcdefghijk", "Video unavailable")
            assertTrue(rejected.await(5, TimeUnit.SECONDS)); assertEquals(0, destination.get())
        } finally { webhook.close(); scope.cancel(); server.stop(0) }
    }
}
