package ca.saboor.discs.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MixPlanCommitmentTest {
    private val ready = MixPlan(172000, 8000, cueMs = 37000, beatAligned = true, tempoFactor = 0.98)
    @Test fun `announced cue and actual plan survive later analysis fluctuations`() {
        val commitment = MixPlanCommitment<String>()
        assertEquals(ready, commitment.resolve("song-pair", ready))
        assertEquals(ready, commitment.resolve("song-pair", MixPlan(176000, 4000)))
        assertEquals(ready, commitment.resolve("song-pair", ready.copy(cueMs = 8000, startMs = 173000)))
    }
    @Test fun `playback context changes and disabling allow a new plan`() {
        val commitment = MixPlanCommitment<String>()
        commitment.resolve("pair", ready)
        val fallback = MixPlan(176000, 4000)
        assertEquals(fallback, commitment.resolve("changed", fallback))
        commitment.resolve("changed", ready)
        assertEquals(fallback, commitment.resolve(null, fallback))
        assertEquals(fallback, commitment.resolve("changed", fallback))
    }
    @Test fun `uncertain estimates remain provisional until a confident match exists`() {
        val commitment = MixPlanCommitment<String>()
        val fallback = MixPlan(176000, 4000)
        assertEquals(fallback, commitment.resolve("pair", fallback))
        assertEquals(ready, commitment.resolve("pair", ready))
        commitment.clear()
        assertEquals(fallback, commitment.resolve("pair", fallback))
    }
}
