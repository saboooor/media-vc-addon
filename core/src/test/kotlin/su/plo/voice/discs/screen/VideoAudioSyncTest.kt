package su.plo.voice.discs.screen

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
    @Test fun `frame timing uses presentation timestamps including fractional and slow sources`() {
        assertEquals(417L, VideoAudioSync.timestamp("[Parsed_showinfo_3 @ 0x1] n: 10 pts: 417 pts_time:0.417 duration_time:0.0417"))
        assertEquals(2000L, VideoAudioSync.timestamp("[Parsed_showinfo_3 @ 0x1] n: 1 pts: 2 pts_time:2 duration_time:2"))
        assertNull(VideoAudioSync.timestamp("out_time_us=500000"))
    }
}
