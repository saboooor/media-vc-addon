package ca.saboor.discs.visualizer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LightFalloffTest {
    @Test fun `higher retention keeps a flash lit longer`() {
        val crisp = BassLampEnvelope()
        val slow = BassLampEnvelope()
        assertTrue(crisp.update(1f))
        assertTrue(slow.update(1f, 0.8))
        assertFalse(crisp.update(0f))
        assertTrue(slow.update(0f, 0.8))
        repeat(20) { slow.update(0f, 0.8) }
        assertFalse(slow.lit)
    }

    @Test fun `live falloff changes affect an existing pulse`() {
        val envelope = BassLampEnvelope()
        envelope.update(1f, 1.0)
        repeat(20) { assertTrue(envelope.update(0f, 1.0)) }
        assertFalse(envelope.update(0f, 0.0))
    }

    @Test fun `out of range settings clamp and nonfinite settings use the default`() {
        val low = BassLampEnvelope()
        low.update(1f)
        assertFalse(low.update(0f, -1.0))
        val high = BassLampEnvelope()
        high.update(1f)
        assertTrue(high.update(0f, 2.0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val envelope = BassLampEnvelope()
            envelope.update(1f)
            assertFalse(envelope.update(0f, invalid))
        }
    }

    @Test fun `automatic falloff scales with tempo`() {
        val slowBpm = ca.saboor.discs.PlaybackControls.falloffForBpm(60.0)
        val midBpm = ca.saboor.discs.PlaybackControls.falloffForBpm(120.0)
        val fastBpm = ca.saboor.discs.PlaybackControls.falloffForBpm(180.0)

        assertEquals(0.60, slowBpm)
        assertEquals(0.35, midBpm)
        assertEquals(0.10, fastBpm)
        assertTrue(slowBpm > midBpm)
        assertTrue(midBpm > fastBpm)
    }

    @Test fun `automatic falloff from beat interval accounts for playback rate`() {
        // 500ms beat interval = 120 BPM at 1.0x rate
        val normal = ca.saboor.discs.PlaybackControls.falloffForBeatMs(500, 1.0)
        assertEquals(0.35, normal)

        // 500ms beat interval at 1.25x speed = 150 BPM effective
        val nightcore = ca.saboor.discs.PlaybackControls.falloffForBeatMs(500, 1.25)
        assertTrue(nightcore < normal)

        // 500ms beat interval at 0.8x speed = 96 BPM effective
        val vaporwave = ca.saboor.discs.PlaybackControls.falloffForBeatMs(500, 0.8)
        assertTrue(vaporwave > normal)
    }

    @Test fun `extreme tempos clamp within safe retention bounds`() {
        val ultraSlow = ca.saboor.discs.PlaybackControls.falloffForBpm(20.0)
        val ultraFast = ca.saboor.discs.PlaybackControls.falloffForBpm(300.0)
        assertEquals(0.60, ultraSlow)
        assertEquals(0.10, ultraFast)
    }

    @Test fun `realistic noisy drum rhythm detects tempo and calculates falloff`() {
        val analysis = ca.saboor.discs.audio.MixAnalysis()
        // Simulate a real song: 0.25 continuous background music with 120 BPM kicks (period = 25 frames)
        repeat(200) { i ->
            val isKick = i % 25 == 0
            val background = 0.25 + 0.05 * kotlin.math.sin(i.toDouble())
            val energy = if (isKick) 0.55 else background
            analysis.add(i * 20L, energy)
        }
        val features = analysis.snapshot()
        assertNotNull(features.beatMs)
        assertEquals(500L, features.beatMs)

        val falloff = ca.saboor.discs.PlaybackControls.falloffForBeatMs(features.beatMs!!)
        assertEquals(0.35, falloff)
    }

    @Test fun `normal sustained bass or highs stay solidly lit while very loud sustained sections flicker`() {
        val normalSustain = BassLampEnvelope()
        assertTrue(normalSustain.update(0.70f))
        repeat(15) {
            assertTrue(normalSustain.update(0.60f), "Strong sustained bass/highs should stay solidly lit without flickering")
            assertFalse(normalSustain.isBuzzing)
        }
        assertFalse(normalSustain.update(0f))

        val loudBuzz = BassLampEnvelope()
        assertTrue(loudBuzz.update(0.95f))
        assertTrue(loudBuzz.update(0.90f))
        val states = (1..20).map { loudBuzz.update(0.90f) }
        assertTrue(loudBuzz.isBuzzing)
        assertTrue(states.any { it }, "Very loud sustained buzz should flash on")
        assertTrue(states.any { !it }, "Very loud sustained buzz should flicker off between pulses")
        assertFalse(loudBuzz.update(0f))
    }

    @Test fun `smooth swells stay solidly lit on sustained passages and flicker only when peaking very loud`() {
        val envelope = BassLampEnvelope()
        for (level in listOf(0.15f, 0.30f, 0.45f, 0.58f)) {
            envelope.update(level)
        }
        repeat(10) {
            assertTrue(envelope.update(0.58f), "Smooth sustained swells should hold solid on")
        }
        // Swell up to a very loud sustained peak
        val loudStates = (1..20).map { envelope.update(0.90f) }
        assertTrue(loudStates.any { it })
        assertTrue(loudStates.any { !it }, "Once the sustained passage reaches very loud levels, it should flicker")
        assertFalse(envelope.update(0f))
    }

    @Test fun `steady background music between beats turns off instead of staying permanently lit`() {
        val envelope = BassLampEnvelope()
        // Simulate a song with 0.35 continuous background instrumentation and periodic 0.80 beats
        var offCount = 0
        var onCount = 0
        for (tick in 0 until 60) {
            val isBeat = tick % 10 == 0
            val lit = envelope.update(if (isBeat) 0.80f else 0.35f, falloff = 0.2)
            if (lit) onCount++ else offCount++
        }
        assertTrue(onCount >= 6, "Beats should flash the light on")
        assertTrue(offCount >= 30, "Steady background music between beats should let the light turn off")
    }

    @Test fun `rolling peak automatic gain normalization triggers lights on quiet tracks and increments beat count`() {
        val envelope = BassLampEnvelope()
        // Feed quiet background frames so rollingPeak decays to its quiet floor
        repeat(200) { envelope.update(0.03f) }
        // A quiet transient (0.12f) that would fail unnormalized threshold (0.12*3 = 0.36 < 0.40) triggers cleanly with AGC
        assertTrue(envelope.update(0.12f))
        assertEquals(1, envelope.beatCount)
        assertFalse(envelope.update(0.0f))
        assertTrue(envelope.update(0.12f))
        assertEquals(2, envelope.beatCount)
    }
}
