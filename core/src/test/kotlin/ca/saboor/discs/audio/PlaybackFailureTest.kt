package ca.saboor.discs.audio

import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaybackFailureTest {
    @Test fun `authentication and bot challenges retain burned discs`() {
        for (message in listOf("Sign in to confirm you’re not a bot", "Please SIGN IN to confirm your age",
            "Login required", "OAuth token expired", "Authentication failed", "CAPTCHA required",
            "Bot verification required", "YouTube detected you as a bot", "Bot detected", "Too many requests (HTTP 429)")) {
            assertTrue(PlaybackFailure.keepDisc(message), message)
            assertTrue(PlaybackFailure.keepDisc(IllegalStateException(message)), message)
        }
    }
    @Test fun `nested and suppressed provider errors survive generic wrappers`() {
        val nested = CompletionException(IllegalStateException("Something went wrong", RuntimeException("Sign in to confirm you're not a bot")))
        assertTrue(PlaybackFailure.keepDisc(nested))
        assertTrue(PlaybackFailure.keepDisc(PlaybackFailure.message(nested, "Audio failed")))
        val suppressed = RuntimeException("All requests failed").apply { addSuppressed(RuntimeException("OAuth authorization expired")) }
        assertTrue(PlaybackFailure.keepDisc(suppressed))
        class AllClientsFailedException : RuntimeException("All clients failed")
        assertTrue(PlaybackFailure.keepDisc(AllClientsFailedException()))
    }
    @Test fun `unavailable media keeps the existing erase behavior`() {
        for (message in listOf("Video unavailable", "Video has been removed", "Audio lookup timed out after 30 seconds", "Audio stream failed", "Robot song could not be decoded")) {
            assertFalse(PlaybackFailure.keepDisc(message), message)
            assertEquals(message, PlaybackFailure.message(RuntimeException(message), "Fallback"))
        }
        assertFalse(PlaybackFailure.keepDisc(null))
    }
    @Test fun `cyclic exception causes cannot loop forever`() {
        val first = RuntimeException("Wrapper")
        val second = RuntimeException("Another wrapper", first)
        first.initCause(second)
        assertFalse(PlaybackFailure.keepDisc(first))
    }
}
