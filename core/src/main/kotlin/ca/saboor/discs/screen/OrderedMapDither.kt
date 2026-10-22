package ca.saboor.discs.screen

/** Fixed Bayer pattern in screen coordinates; no per-frame noise or error propagation. */
internal class OrderedMapDither(strength: Int) {
    private val offsets = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    ).map { ((it * 2 - 15) * strength.coerceIn(0, 32)) / 16 }.toIntArray()

    fun lookup(r: Int, g: Int, b: Int, x: Int, y: Int): Byte {
        val offset = offsets[(y and 3) * 4 + (x and 3)]
        // Preserve solid black letterboxing, white, and saturated channel endpoints.
        fun adjust(value: Int) = if (value == 0 || value == 255) value else (value + offset).coerceIn(0, 255)
        return MapPaletteLookup.lookup(adjust(r), adjust(g), adjust(b))
    }
}
