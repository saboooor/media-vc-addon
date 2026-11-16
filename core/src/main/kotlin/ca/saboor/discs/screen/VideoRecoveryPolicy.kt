package ca.saboor.discs.screen

/** Bound retries without giving a long movie a lifetime limit on recovered interruptions. */
internal class VideoRecoveryPolicy {
    var attempts: Int = 0
        private set
    fun allow(decodedDurationMs: Long): Boolean {
        // Audio advances even when video never starts; only decoded video resets retries.
        if (decodedDurationMs >= 10_000L) attempts = 0
        return ++attempts <= 3
    }
}
