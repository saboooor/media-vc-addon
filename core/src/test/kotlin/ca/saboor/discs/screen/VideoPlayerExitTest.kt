package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoPlayerExitTest {
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
