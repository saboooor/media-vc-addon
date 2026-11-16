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
    @Test fun `slow movie decoding presents first frame and cannot discard every subsequent frame`() {
        val delivery = VideoFrameDelivery()
        // Audio has started before the expensive movie decoder produces its first frame.
        assertFalse(delivery.shouldDrop(0, 1500, 0))
        assertTrue(delivery.shouldDrop(40, 1540, 40_000_000))
        assertTrue(delivery.shouldDrop(480, 1980, 480_000_000))
        assertFalse(delivery.shouldDrop(520, 2020, 520_000_000))
        // Sustained decoding lag still refreshes the screen instead of leaving it black/frozen.
        assertTrue(delivery.shouldDrop(600, 3000, 600_000_000))
        assertFalse(delivery.shouldDrop(1040, 5000, 1_040_000_000))
        // Once decoding catches up, every timely frame is presented normally.
        assertFalse(delivery.shouldDrop(5000, 5050, 1_080_000_000))
        assertFalse(delivery.shouldDrop(5040, 5090, 1_120_000_000))
    }

    @Test fun `late frames are presented when decode buffer has no newer frame queued`() {
        val delivery = VideoFrameDelivery()
        assertFalse(delivery.shouldDrop(0, 5540, 0, hasQueuedFrames = false))
        assertTrue(delivery.shouldDrop(20, 5560, 10_000_000, hasQueuedFrames = false))
        assertFalse(delivery.shouldDrop(42, 5582, 42_000_000, hasQueuedFrames = false))
        assertFalse(delivery.shouldDrop(83, 5623, 83_000_000, hasQueuedFrames = false))
        assertTrue(delivery.shouldDrop(125, 5665, 125_000_000, hasQueuedFrames = true))
    }

    @Test fun `frame timing uses presentation timestamps including fractional and slow sources`() {
        assertEquals(417L, VideoAudioSync.timestamp("[Parsed_showinfo_3 @ 0x1] n: 10 pts: 417 pts_time:0.417 duration_time:0.0417"))
        assertEquals(2000L, VideoAudioSync.timestamp("[Parsed_showinfo_3 @ 0x1] n: 1 pts: 2 pts_time:2 duration_time:2"))
        assertNull(VideoAudioSync.timestamp("out_time_us=500000"))
    }
}

