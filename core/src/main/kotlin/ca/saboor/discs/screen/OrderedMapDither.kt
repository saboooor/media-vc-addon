package ca.saboor.discs.screen

import kotlin.math.floor
import kotlin.math.roundToInt

enum class DitherMode(val id: String, val label: String) {
    BAYER("bayer", "Bayer 4×4"),
    IGN("ign", "IGN (Blue-noise)");

    fun next(): DitherMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(value: String?): DitherMode = when (value?.trim()?.lowercase()) {
            "bayer", "ordered" -> BAYER
            "ign", "bluenoise", "blue-noise", "blue_noise" -> IGN
            else -> IGN
        }
    }
}

/** Deterministic spatial dither in screen coordinates; no per-frame noise or error propagation. */
internal class OrderedMapDither(
    strength: Int,
    val mode: DitherMode = DitherMode.BAYER
) {
    val strength: Int = strength.coerceIn(0, 32)
    private val levelOffsets = IntArray(16) { ((it * 2 - 15) * this.strength) / 16 }
    private val bayerOffsets = IntArray(16) { levelOffsets[BAYER_4X4[it]] }

    fun lookup(r: Int, g: Int, b: Int, x: Int, y: Int): Byte {
        if (strength == 0) return MapPaletteLookup.lookup(r, g, b)
        val offset = when (mode) {
            DitherMode.BAYER -> bayerOffsets[((y and 3) shl 2) or (x and 3)]
            DitherMode.IGN -> levelOffsets[IGN_128[((y and 127) shl 7) or (x and 127)].toInt()]
        }
        // Preserve solid black letterboxing, white, and saturated channel endpoints.
        fun adjust(value: Int) = if (value == 0 || value == 255) value else (value + offset).coerceIn(0, 255)
        return MapPaletteLookup.lookup(adjust(r), adjust(g), adjust(b))
    }

    companion object {
        const val FULL_DITHER_DISTANCE = 6.0
        const val ZERO_DITHER_DISTANCE = 24.0

        private val BAYER_4X4 = intArrayOf(
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
        )

        /**
         * Jiménez Interleaved Gradient Noise (128x128 tile), quantized to 16 levels.
         * Avoids the 2- and 4-pixel periodic harmonics of Bayer 4x4 that cause moire under GL_NEAREST.
         */
        private val IGN_128 = ByteArray(128 * 128) { index ->
            val x = index and 127
            val y = index ushr 7
            val grad = 0.06711056 * x + 0.00583715 * y
            val noise = 52.9829189 * (grad - floor(grad))
            ((noise - floor(noise)) * 16.0).toInt().coerceIn(0, 15).toByte()
        }

        /**
         * Scales dithering strength down as viewers move past the distance where a 128x128
         * map tile subtends less than 128 screen pixels, preventing GL_NEAREST minification moire.
         */
        fun adaptiveStrength(
            baseStrength: Int,
            distanceBlocks: Double,
            fullDistanceBlocks: Double = FULL_DITHER_DISTANCE,
            zeroDistanceBlocks: Double = ZERO_DITHER_DISTANCE
        ): Int {
            val clamped = baseStrength.coerceIn(0, 32)
            if (clamped == 0 || !distanceBlocks.isFinite() || distanceBlocks <= fullDistanceBlocks) return clamped
            if (distanceBlocks >= zeroDistanceBlocks || zeroDistanceBlocks <= fullDistanceBlocks) return 0
            val factor = (zeroDistanceBlocks - distanceBlocks) / (zeroDistanceBlocks - fullDistanceBlocks)
            val scaled = (clamped * factor).roundToInt().coerceIn(0, clamped)
            // Quantize intermediate strengths to even steps so minor player movement does not churn tile caches.
            return if (scaled <= 1) 0 else ((scaled + 1) and -2).coerceAtMost(clamped)
        }
    }
}
