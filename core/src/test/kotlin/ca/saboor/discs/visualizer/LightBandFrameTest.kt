package ca.saboor.discs.visualizer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LightBandFrameTest {
    @Test fun `disabled chase and ripple give both sides identical pulses and colors`() {
        for (pulse in listOf(LightPulse(true, 3, false, 7), LightPulse(false, 4, true, 8), LightPulse(true, 4, true, 8))) {
            val frame = LightBandFrame(listOf(pulse, LightPulse()), playing = true, ripple = false, chase = false)
            assertEquals(pulse, frame.sample(0.0, 0))
            assertEquals(pulse, frame.sample(32.0, 1))
        }
    }

    @Test fun `ripple delays distant lamps only when enabled`() {
        val current = LightPulse(true, 3, false, 7)
        val previous = LightPulse(false, 2, false, 6)
        val frame = LightBandFrame(listOf(current, previous), playing = true, ripple = true, chase = false)
        assertEquals(current, frame.sample(0.0, 0))
        assertEquals(previous, frame.sample(3.0, 1))
        assertEquals(previous, frame.sample(100.0, 0))
    }

    @Test fun `chase alternates beats and buzzing only when enabled`() {
        val beat = LightBandFrame(listOf(LightPulse(true, 3, false, 7)), true, false, true)
        assertFalse(beat.sample(0.0, 0).lit)
        assertTrue(beat.sample(0.0, 1).lit)
        assertEquals(8, beat.sample(0.0, 1).colorStep)
        val buzz = LightBandFrame(listOf(LightPulse(false, 4, true, 8)), true, false, true)
        assertFalse(buzz.sample(0.0, 0).lit)
        assertTrue(buzz.sample(0.0, 1).lit)
    }

    @Test fun `inactive source keeps every lamp off even during a chase buzz`() {
        val frame = LightBandFrame(listOf(LightPulse(false, 4, true, 8)), false, true, true)
        assertFalse(frame.sample(0.0, 0).lit)
        assertFalse(frame.sample(32.0, 1).lit)
        assertFalse(LightBandFrame(emptyList(), true, false, false).sample(0.0, 0).lit)
    }

    @Test fun `ripple and chase speeds scale delay and alternation rate`() {
        val t0 = LightPulse(true, 0, false, 1)
        val t1 = LightPulse(false, 0, false, 1)
        val t2 = LightPulse(true, 1, false, 2)
        // Faster ripple reduces delay for the same distance
        val slowRipple = LightBandFrame(listOf(t0, t1, t2), playing = true, ripple = true, chase = false, rippleSpeed = 0.5)
        val fastRipple = LightBandFrame(listOf(t0, t1, t2), playing = true, ripple = true, chase = false, rippleSpeed = 2.0)
        assertEquals(t2, slowRipple.sample(3.0, 0))
        assertEquals(t0, fastRipple.sample(3.0, 0))

        // Half-speed chase holds parity across two beats; 2x chase alternates within a beat via tickCount
        val halfChaseBeat0 = LightBandFrame(listOf(LightPulse(true, 0)), true, false, true, chaseSpeed = 0.5)
        val halfChaseBeat1 = LightBandFrame(listOf(LightPulse(true, 1)), true, false, true, chaseSpeed = 0.5)
        val halfChaseBeat2 = LightBandFrame(listOf(LightPulse(true, 2)), true, false, true, chaseSpeed = 0.5)
        assertTrue(halfChaseBeat0.sample(0.0, 0).lit)
        assertTrue(halfChaseBeat1.sample(0.0, 0).lit)
        assertFalse(halfChaseBeat2.sample(0.0, 0).lit)

        val fastChaseA = LightBandFrame(listOf(LightPulse(true, 0)), true, false, true, chaseSpeed = 2.0, tickCount = 0)
        val fastChaseB = LightBandFrame(listOf(LightPulse(true, 0)), true, false, true, chaseSpeed = 2.0, tickCount = 4)
        assertTrue(fastChaseA.sample(0.0, 0).lit)
        assertFalse(fastChaseB.sample(0.0, 0).lit)
    }
}
