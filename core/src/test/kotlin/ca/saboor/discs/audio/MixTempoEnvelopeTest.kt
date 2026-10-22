package ca.saboor.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MixTempoEnvelopeTest {
    @Test fun `both faster and slower matches ramp smoothly while retaining their tempo ratio`() {
        for (match in listOf(0.92, 0.96, 1.0, 1.04, 1.08)) {
            val ramp = MixTempoEnvelope(match, 1.0, 8000)
            assertEquals(1.0 to match, ramp.factors(0))
            val end = ramp.factors(8000)
            assertEquals(1.0, end.first * end.second, 1e-9)
            for (time in 0L..8000L step 20) {
                val (outgoing, incoming) = ramp.factors(time)
                assertTrue(outgoing in 0.92..1.08)
                assertTrue(incoming in 0.92..1.08)
                assertEquals(match, incoming / outgoing, 1e-9)
            }
            val midway = ramp.factors(1400)
            assertEquals((1.0 + end.first) / 2, midway.first, 1e-9)
            assertEquals(ramp.factors(2800), end)
        }
    }
    @Test fun `existing outgoing adjustments keep both tracks within stretching limits`() {
        for (start in listOf(0.92, 1.08)) for (match in listOf(0.92, 1.08)) {
            val ramp = MixTempoEnvelope(match, start, 8000)
            for (time in 0L..8000L step 20) {
                val (outgoing, incoming) = ramp.factors(time)
                assertTrue(outgoing >= 0.92 - 1e-9 && outgoing <= 1.08 + 1e-9)
                assertTrue(incoming >= 0.92 - 1e-9 && incoming <= 1.08 + 1e-9)
                assertEquals(match / start, incoming / outgoing, 1e-9)
            }
        }
    }
}
