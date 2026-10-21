package su.plo.voice.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AutoMixTest {
    private fun rhythm(period: Int = 25, silence: Int = 0): MixFeatures {
        val analysis = MixAnalysis()
        repeat(400) { i -> analysis.add(i * 20L, if (i < silence) 0.0 else if ((i - silence) % period == 0) 0.7 else 0.05) }
        return analysis.snapshot()
    }
    @Test fun `steady 120 bpm pulses produce a confident beat estimate`() {
        val result = rhythm()
        assertEquals(500L, result.beatMs)
        assertTrue(result.confidence > 0.9)
        assertTrue(result.rms > 0)
    }
    @Test fun `sustained or silent audio never invents a tempo`() {
        val analysis = MixAnalysis()
        repeat(800) { analysis.add(it * 20L, 0.2) }
        assertNull(analysis.snapshot().beatMs)
        analysis.reset()
        repeat(400) { analysis.add(it * 20L, 0.0) }
        assertNull(analysis.snapshot().beatMs)
        assertEquals(0.0, analysis.snapshot().rms)
    }
    @Test fun `compatible songs align an eight beat blend and trim intro silence`() {
        val outgoing = rhythm()
        val incoming = rhythm(silence = 50)
        val plan = AutoMix.plan(180_321, 200_000, outgoing, incoming)
        assertTrue(plan.beatAligned)
        assertEquals(4_000L, plan.durationMs)
        assertEquals(0L, Math.floorMod(plan.startMs - outgoing.beatAnchorMs, 500L))
        assertTrue(plan.cueMs in 1_000L..1_500L)
        assertTrue(plan.startMs + plan.durationMs <= 180_321)
    }
    @Test fun `uncertain or incompatible matches fall back and short songs keep bounded fades`() {
        val plan = AutoMix.plan(100_000, 100_000, rhythm(), rhythm(period = 40))
        assertFalse(plan.beatAligned)
        assertTrue(plan.durationMs in 2_000..4_000)
        val short = AutoMix.plan(1_000, 1_500, rhythm(), rhythm())
        assertTrue(short.durationMs <= 334)
        assertTrue(short.startMs >= 0)
        assertTrue(short.startMs + short.durationMs <= 1_000)
    }
    @Test fun `equal power fade has no mid transition energy dip and preserves interrupted gain`() {
        for (i in 0..100) {
            val gains = AutoMix.gains(i / 100.0)
            assertEquals(1.0, gains.first * gains.first + gains.second * gains.second, 1e-9)
        }
        assertEquals(0.4, AutoMix.gains(0.0, outgoingStart = 0.4).first, 1e-9)
        assertEquals(0.0, AutoMix.gains(0.0).second, 1e-9)
        assertTrue(abs(AutoMix.gains(1.0).first) < 1e-9)
        assertEquals(1.0, AutoMix.gains(1.0).second, 1e-9)
    }
    @Test fun `fade clock waits for incoming frames and stays frozen during buffering or pause`() {
        val fade = MixEnvelope(3000)
        repeat(100) {
            assertEquals(1.0, fade.gains(0).first, 1e-9)
            assertEquals(0.0, fade.gains(0).second, 1e-9)
            assertFalse(fade.complete(0))
        }
        val halfway = fade.gains(1500)
        assertEquals(halfway, fade.gains(1500))
        assertFalse(fade.complete(1500))
        assertTrue(fade.complete(3000))
    }
    @Test fun `close tempos match in the correct direction then recover smoothly`() {
        val outgoing = MixFeatures(rms = 0.1, beatMs = 500, confidence = 0.9)
        val incoming = MixFeatures(rms = 0.1, beatMs = 480, confidence = 0.9)
        val plan = AutoMix.plan(100_000, 100_000, outgoing, incoming)
        assertTrue(plan.beatAligned)
        assertEquals(0.96, plan.tempoFactor, 1e-9)
        assertEquals(500.0, 480.0 / plan.tempoFactor, 1e-9)
        assertEquals(0.96, AutoMix.releaseTempo(0.96, 0), 1e-9)
        assertEquals(0.98, AutoMix.releaseTempo(0.96, 2000), 1e-9)
        assertEquals(1.0, AutoMix.releaseTempo(0.96, 4000), 1e-9)
        assertEquals(1.0, AutoMix.plan(100000, 100000, outgoing, incoming.copy(beatMs = 300)).tempoFactor)
    }

    @Test fun `a louder next song is attenuated during the blend and quiet songs are not boosted`() {
        val plan = AutoMix.plan(100_000, 100_000, MixFeatures(rms = 0.1), MixFeatures(rms = 0.2))
        assertEquals(0.5, plan.incomingGain)
        val quiet = AutoMix.plan(100_000, 100_000, MixFeatures(rms = 0.2), MixFeatures(rms = 0.1))
        assertEquals(1.0, quiet.incomingGain)
    }
}
