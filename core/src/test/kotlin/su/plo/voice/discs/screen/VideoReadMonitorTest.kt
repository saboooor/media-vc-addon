package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoReadMonitorTest {
    @Test fun `a blocked read times out even without decoder stderr`() {
        val monitor = VideoReadMonitor(15)
        monitor.beginRead(100)
        assertFalse(monitor.isStalled(114, false))
        assertTrue(monitor.isStalled(115, false))
    }

    @Test fun `completed frames and rendering time never trigger a stall`() {
        val monitor = VideoReadMonitor(15)
        assertFalse(monitor.isStalled(100, false))
        monitor.beginRead(100)
        monitor.endRead()
        assertFalse(monitor.isStalled(1000, false))
        monitor.beginRead(1000)
        assertFalse(monitor.isStalled(1014, false))
    }

    @Test fun `paused reads receive a fresh timeout on resuming`() {
        val monitor = VideoReadMonitor(15)
        monitor.beginRead(0)
        assertFalse(monitor.isStalled(100, true))
        assertFalse(monitor.isStalled(200, true))
        assertFalse(monitor.isStalled(214, false))
        assertTrue(monitor.isStalled(215, false))
    }
}
