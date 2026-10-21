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
    @Test fun `later rhythmic sections cue past a slow intro only for a compatible mix`() {
        val outgoing = rhythm()
        val incoming = rhythm().copy(mixCueMs = 24_500L)
        assertEquals(24_500L, AutoMix.plan(180000, 200000, outgoing, incoming).cueMs)
        val fallback = AutoMix.plan(180000, 200000, outgoing, incoming.copy(beatMs = null))
        assertFalse(fallback.beatAligned)
        assertEquals(incoming.audibleStartMs, fallback.cueMs)
        val short = AutoMix.plan(180000, 25000, outgoing, incoming)
        assertTrue(short.cueMs + short.durationMs < 25000)
    }

    @Test fun `current song keeps scanning after a slow intro without retaining its energy window`() {
        val analysis = MixAnalysis(scanPastUncertainSections = true)
        repeat(400) { i -> analysis.add(i * 20L, 0.2); if (i % 25 == 0) analysis.snapshot() }
        assertNull(analysis.snapshot().beatMs)
        repeat(200) { i ->
            analysis.add(8000L + i * 20L, if (i % 25 == 0) 0.7 else 0.05)
            if (i % 25 == 0) analysis.snapshot()
        }
        val later = analysis.snapshot()
        assertEquals(500L, later.beatMs)
        assertTrue(later.audibleStartMs >= 8000)
        assertTrue(later.beatAnchorMs >= 8000)
        analysis.reset()
        assertNull(analysis.snapshot().beatMs)
    }

    @Test fun `matched blends hand over bass at the middle and keep equal power`() {
        val mix = MixEnvelope(8000, beatMatched = true)
        assertEquals(1.0 to 0.0, mix.bassGains(0))
        assertEquals(0.5, mix.bassGains(4000).first, 1e-9)
        assertEquals(0.5, mix.bassGains(4000).second, 1e-9)
        assertEquals(0.0 to 1.0, mix.bassGains(8000))
        for (time in 0L..8000L step 100) {
            val (a, b) = mix.gains(time)
            assertEquals(1.0, a * a + b * b, 1e-9)
        }
        assertEquals(1.0 to 1.0, MixEnvelope(2000).bassGains(1000))
    }

    @Test fun `later compatible section replaces a confident incompatible intro`() {
        val outgoing = MixFeatures(rms = 0.1, beatMs = 500, confidence = 0.9)
        val intro = outgoing.copy(beatMs = 700)
        val later = outgoing.copy(mixCueMs = 24000)
        assertEquals(later, AutoMix.chooseSection(outgoing, listOf(intro, later), 180000))
    }

    @Test fun `section ranking avoids needless skipping but rewards stronger matches`() {
        val outgoing = MixFeatures(rms = 0.1, beatMs = 500, confidence = 0.9)
        val equivalentLater = outgoing.copy(mixCueMs = 16000)
        assertEquals(outgoing, AutoMix.chooseSection(outgoing, listOf(outgoing, equivalentLater), 180000))
        val weakIntro = outgoing.copy(confidence = 0.56, beatMs = 540, rms = 0.25)
        assertEquals(equivalentLater, AutoMix.chooseSection(outgoing, listOf(weakIntro, equivalentLater), 180000))
        assertEquals(weakIntro, AutoMix.chooseSection(outgoing, listOf(weakIntro, equivalentLater), 18000))
        assertEquals(weakIntro, AutoMix.chooseSection(outgoing.copy(beatMs = null), listOf(weakIntro, equivalentLater), 180000))
    }

    @Test fun `modestly better later sections can now beat a usable intro`() {
        val intro = MixFeatures(rms = 0.1, beatMs = 500, confidence = 0.90)
        val later = intro.copy(confidence = 0.98, mixCueMs = 32000)
        assertEquals(later, AutoMix.chooseSection(intro, listOf(intro, later), 180000))
        assertEquals(intro, AutoMix.chooseSection(intro, listOf(intro, later.copy(confidence = 0.90)), 180000))
    }

    @Test fun `blend brings incoming audio up early and holds both songs through the middle`() {
        val mix = MixEnvelope(10000, beatMatched = true)
        assertTrue(mix.gains(2500).second > 0.6)
        assertTrue(mix.gains(7500).first > 0.6)
        for (time in 3000L..7000L step 100) {
            val (outgoing, incoming) = mix.gains(time)
            assertEquals(kotlin.math.sqrt(0.5), outgoing, 1e-9)
            assertEquals(outgoing, incoming, 1e-9)
        }
        assertEquals(1.0, mix.gains(0).first, 1e-9)
        assertEquals(0.0, mix.gains(0).second, 1e-9)
        assertEquals(0.0, mix.gains(10000).first, 1e-9)
        assertEquals(1.0, mix.gains(10000).second, 1e-9)
        val attenuated = MixEnvelope(10000, 0.8, 0.5, true)
        assertEquals(0.8 * kotlin.math.sqrt(0.5), attenuated.gains(5000).first, 1e-9)
        assertEquals(0.5 * kotlin.math.sqrt(0.5), attenuated.gains(5000).second, 1e-9)
    }

    @Test fun `strong late sections can commit an earlier mix out while weak and early sections cannot`() {
        val features = MixFeatures(rms = 0.1, beatMs = 500, confidence = 0.95)
        val original = AutoMix.plan(180000, 200000, features, features)
        val earlier = AutoMix.earlierExit(original, 180000, 140000, features, features)
        assertTrue(earlier.earlyExit)
        assertTrue(earlier.startMs >= 142000)
        assertTrue(earlier.startMs + earlier.durationMs < original.startMs)
        assertEquals(original, AutoMix.earlierExit(original, 180000, 30000, features, features))
        assertEquals(original, AutoMix.earlierExit(original, 180000, 140000, features.copy(confidence = 0.6), features))
        assertEquals(original, AutoMix.earlierExit(original, 180000, 140000, features, features.copy(rms = 0.01)))
        val commitment = MixPlanCommitment<String>()
        commitment.resolve("pair", original)
        assertEquals(earlier, commitment.resolve("pair", earlier))
        assertEquals(earlier, commitment.resolve("pair", original))
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
    @Test fun `compatible songs align a sixteen beat blend and trim intro silence`() {
        val outgoing = rhythm()
        val incoming = rhythm(silence = 50)
        val plan = AutoMix.plan(180_321, 200_000, outgoing, incoming)
        assertTrue(plan.beatAligned)
        assertEquals(8_000L, plan.durationMs)
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
