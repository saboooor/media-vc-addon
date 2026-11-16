package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoPlayerExitTest {
    @Test fun `truncated movie stream is recovered but real endings remain finished`() {
        assertTrue(VideoPlayer.streamEndedEarly(6_420_000, 49_299))
        assertTrue(VideoPlayer.streamEndedEarly(6_420_000, 0))
        assertFalse(VideoPlayer.streamEndedEarly(6_420_000, 6_419_000))
        assertFalse(VideoPlayer.streamEndedEarly(6_420_000, 6_420_000))
        assertFalse(VideoPlayer.streamEndedEarly(0, 49_299))
    }

    @Test fun `repeated failures without progress stop after three retries`() {
        val recovery = VideoRecoveryPolicy()
        assertTrue(recovery.allow(0))
        assertTrue(recovery.allow(1_000))
        assertTrue(recovery.allow(2_000))
        assertFalse(recovery.allow(3_000))
    }

    @Test fun `long movies can recover later interruptions after making progress`() {
        val recovery = VideoRecoveryPolicy()
        repeat(120) {
            assertTrue(recovery.allow(50_000))
            assertEquals(1, recovery.attempts)
        }
    }

    @Test fun `seek and stop termination are not playback failures`() {
        assertFalse(VideoPlayer.shouldReportExit(137, cancelled = true, stopped = false))
        assertFalse(VideoPlayer.shouldReportExit(137, cancelled = false, stopped = true))
        assertFalse(VideoPlayer.shouldReportExit(null, cancelled = false, stopped = false))
        assertFalse(VideoPlayer.shouldReportExit(0, cancelled = false, stopped = false))
    }

    @Test fun `unexpected SIGKILL and decoder failures remain visible`() {
        assertTrue(VideoPlayer.shouldReportExit(137, cancelled = false, stopped = false))
        assertTrue(VideoPlayer.shouldReportExit(1, cancelled = false, stopped = false))
    }

    @Test fun `source diagnostics omit signed query parameters`() {
        assertEquals("https://example.com/videoplayback", VideoPlayer.describeSource(
            "https://example.com/videoplayback?ip=127.0.0.1&sig=secret#fragment"))
    }
}
