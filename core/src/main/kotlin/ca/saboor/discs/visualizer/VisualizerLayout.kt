package ca.saboor.discs.visualizer

/** Session-scoped placement relative to the jukebox; resizing keeps bars anchored at the bottom. */
data class VisualizerLayout(
    val x: Float = 0f, val y: Float = 0f, val z: Float = 0f,
    val width: Float = 1f, val height: Float = 1f,
) {
    fun adjust(dx: Float = 0f, dy: Float = 0f, dz: Float = 0f, dw: Float = 0f, dh: Float = 0f) = copy(
        x = (x + dx).coerceIn(-16f, 16f), y = (y + dy).coerceIn(-16f, 16f), z = (z + dz).coerceIn(-16f, 16f),
        width = (width + dw).coerceIn(0.25f, 4f), height = (height + dh).coerceIn(0.25f, 4f),
    )
}
