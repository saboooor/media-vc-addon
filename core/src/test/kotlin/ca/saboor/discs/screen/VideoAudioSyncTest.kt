package ca.saboor.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoAudioSyncTest {
    @Test fun `video waits when audio buffers including before audio starts`() {
        assertEquals(50L, VideoAudioSync.waitMillis(500, 0))
        assertEquals(0L, VideoAudioSync.waitMillis(500, 500))
        assertEquals(0L, VideoAudioSync.waitMillis(500, 480))
        assertEquals(10L, VideoAudioSync.waitMillis(500, 450))
    }
    @Test fun `video catches up by discarding late frames rather than delaying audio`() {
        assertTrue(VideoAudioSync.stale(1000, 1500))
        assertFalse(VideoAudioSync.stale(1500, 1000))
        assertFalse(VideoAudioSync.stale(1000, 1100))
    }
    @Test fun `stale frames are always skipped when newer frames are queued`() {
        val delivery = VideoFrameDelivery(refreshNanos = 1_000_000_000L / 24)
        var delivered = 0
        repeat(240) { frame ->
            val videoMs = frame * 1000L / 24
            val now = frame * 1_000_000_000L / 24
            if (!delivery.shouldDrop(videoMs, videoMs + 5_000, now, hasQueuedFrames = true)) delivered++
        }
        assertEquals(0, delivered, "All stale frames must be skipped when a newer frame is queued")
    }

    @Test fun `skips stale frames whenever a replacement is queued and presents otherwise`() {
        val delivery = VideoFrameDelivery()
        assertTrue(delivery.shouldDrop(0, 5000, 0, hasQueuedFrames = true))
        assertTrue(delivery.shouldDrop(40, 5040, 1_000_000, hasQueuedFrames = true))
        assertTrue(delivery.shouldDrop(160, 5160, 43_000_000, hasQueuedFrames = true))
        assertFalse(delivery.shouldDrop(120, 5120, 44_000_000, hasQueuedFrames = false))
        assertFalse(delivery.shouldDrop(5200, 5200, 45_000_000, hasQueuedFrames = true))
    }

    @Test fun `frame timing uses presentation timestamps including fractional and slow sources`() {
        assertEquals(417L, VideoAudioSync.timestamp("[Parsed_showinfo_3 @ 0x1] n: 10 pts: 417 pts_time:0.417 duration_time:0.0417"))
        assertEquals(2000L, VideoAudioSync.timestamp("[Parsed_showinfo_3 @ 0x1] n: 1 pts: 2 pts_time:2 duration_time:2"))
        assertNull(VideoAudioSync.timestamp("out_time_us=500000"))
    }
    @Test fun `raw output retains one timestamp per frame across timeline gaps`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false))
        val log = java.nio.file.Files.createTempFile("frame-timestamps", ".log").toFile()
        val command = listOf("ffmpeg", "-hide_banner", "-f", "lavfi", "-i",
            "testsrc=size=16x16:rate=10:duration=1", "-vf", "setpts=2*PTS,showinfo=checksum=0") +
            VideoPlayer.rawVideoOutputOptions() + listOf("-f", "rawvideo", "-pix_fmt", "rgb48le", "pipe:1")
        val process = ProcessBuilder(command).redirectError(log).start()
        try {
            val bytes = process.inputStream.use { it.readBytes() }
            assertEquals(0, process.waitFor(), log.readText())
            val timestamps = log.readLines().mapNotNull(VideoAudioSync::timestamp)
            assertEquals(10, timestamps.size)
            assertEquals(timestamps.size * 16 * 16 * 6, bytes.size)
            assertEquals(1800L, timestamps.last())
        } finally { process.destroyForcibly(); log.delete() }
    }

}
