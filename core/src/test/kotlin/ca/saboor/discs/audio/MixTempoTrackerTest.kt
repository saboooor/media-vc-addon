package ca.saboor.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MixTempoTrackerTest {
    private fun beat(ms: Long?) = MixFeatures(rms = 0.1, beatMs = ms, confidence = if (ms == null) 0.0 else 0.9)

    @Test fun `alternating tempo estimates keep the match stable`() {
        val tracker = MixTempoTracker()
        val incoming = beat(500)
        tracker.update(beat(500), 150)
        for (frames in 175..1000 step 25) {
            val raw = beat(if (frames % 50 == 0) 500 else 560)
            val stable = tracker.update(raw, frames)
            assertEquals(500L, stable.beatMs)
            assertTrue(AutoMix.plan(100000, 100000, stable, incoming).beatAligned)
        }
    }

    @Test fun `sustained tempo changes and uncertain beats eventually replace the estimate`() {
        val tracker = MixTempoTracker()
        tracker.update(beat(500), 150)
        for (frames in 175..250 step 25) assertEquals(500L, tracker.update(beat(600), frames).beatMs)
        assertEquals(600L, tracker.update(beat(600), 275).beatMs)
        tracker.update(beat(null), 300)
        assertNull(tracker.update(beat(null), 400).beatMs)
    }

    @Test fun `repeated polling cannot advance audio evidence and reset forgets old tempo`() {
        val tracker = MixTempoTracker()
        tracker.update(beat(500), 150)
        repeat(1000) { assertEquals(500L, tracker.update(beat(600), 175).beatMs) }
        tracker.reset()
        assertEquals(600L, tracker.update(beat(600), 150).beatMs)
    }
}
