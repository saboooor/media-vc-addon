package su.plo.voice.discs.visualizer

/** Three square layers keep the surround fixed while the diaphragm and dust cap move. */
object SpeakerDriver {
    data class Part(val x: Float, val y: Float, val z: Float,
                    val width: Float, val height: Float, val depth: Float, val moving: Boolean, val cone: Boolean)

    val parts = listOf(
        Part(-0.65f, 0f, 0.30f, 1.3f, 1.3f, 0.10f, false, false),
        Part(-0.52f, 0.13f, 0.405f, 1.04f, 1.04f, 0.035f, true, true),
        Part(-0.23f, 0.42f, 0.445f, 0.46f, 0.46f, 0.07f, true, false)
    )

    fun excursion(pulse: Float) = pulse.coerceIn(0f, 1f) * 0.14f
}
