package su.plo.voice.discs.visualizer

/** Ranges of the shared sixteen-band logarithmic spectrum. */
enum class LightBand(val label: String, private val bands: IntRange) {
    BASS("Bass", 0..4), MID("Mids", 5..10), HIGHS("Highs", 11..15),
    DEEP_BASS("Deep bass", 0..2), LOW_MIDS("Low mids", 4..6),
    VOCALS("Vocal range", 6..9), SPARKLE("Sparkle", 13..15),
    FULL("Full spectrum", 0..15);

    fun level(levels: FloatArray): Float = bands.maxOf { levels.getOrElse(it) { 0f } }
}
