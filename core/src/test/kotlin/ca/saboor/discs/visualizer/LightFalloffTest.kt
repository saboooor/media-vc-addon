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
}
