package ca.saboor.discs.screen

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class VideoTransfer { SDR, PQ, HLG }

internal data class VideoColorInfo(val transfer: VideoTransfer = VideoTransfer.SDR, val bt2020: Boolean = false) {
    companion object {
        /** Read the first input video stream, matching the renderer's -map 0:v:0. */
        fun fromStreamLine(line: String): VideoColorInfo? {
            if (!line.trimStart().startsWith("Stream #0:") || !line.contains(": Video:")) return null
            val transfer = when {
                Regex("\\bsmpte2084\\b").containsMatchIn(line) -> VideoTransfer.PQ
                Regex("\\barib-std-b67\\b").containsMatchIn(line) -> VideoTransfer.HLG
                else -> VideoTransfer.SDR
            }
            // BT.2100 HDR normally uses BT.2020; retain explicit BT.709 primaries if present.
            return VideoColorInfo(transfer, transfer != VideoTransfer.SDR && !line.contains("/bt709/"))
        }
    }
}

/** Convert FFmpeg's full-range RGB48LE to the SDR sRGB bytes used by maps and companion JPEGs.
 * BT.2100 PQ/HLG transfer functions: https://www.itu.int/rec/R-REC-BT.2100
 * LUTs keep transfer-function powers out of the per-pixel rendering loop.
 */
internal class VideoToneMapper(private val info: VideoColorInfo) {
    fun convert(input: ByteArray, output: ByteArray) {
        require(input.size == output.size * 2 && output.size % 3 == 0)
        if (info.transfer == VideoTransfer.SDR) {
            // Match FFmpeg's RGB24 fixed-point rounding without changing the SDR transfer curve.
            for (i in output.indices) output[i] = ((sample(input, i * 2) + 128) shr 8).coerceAtMost(255).toByte()
            return
        }
        val inverse = if (info.transfer == VideoTransfer.PQ) pq else hlg
        val displayLut = srgb
        val gainLut = if (info.transfer == VideoTransfer.HLG) hlgGain else null
        var src = 0
        var dst = 0
        while (dst < output.size) {
            var r = inverse[sample(input, src)]
            var g = inverse[sample(input, src + 2)]
            var b = inverse[sample(input, src + 4)]
            if (info.transfer == VideoTransfer.HLG) {
                val sceneY = (0.2627 * r + 0.6780 * g + 0.0593 * b).coerceIn(0.0, 1.0)
                // BT.2100 reference 1000-nit HLG display, system gamma 1.2.
                val gain = 10.0 * gainLut!![(sceneY * 65535).roundToInt()]
                r *= gain; g *= gain; b *= gain
            }
            if (info.bt2020) {
                // D65 BT.2020 -> BT.709 in linear light, before the display transfer function.
                val red = 1.660491 * r - 0.587641 * g - 0.072850 * b
                val green = -0.124550 * r + 1.132900 * g - 0.008349 * b
                b = -0.018151 * r - 0.100579 * g + 1.118730 * b
                r = red; g = green
            }
            val luminance = max(0.0, 0.2126 * r + 0.7152 * g + 0.0722 * b)
            // Extended Reinhard shoulder: 100-nit exposure, 1000-nit reference peak.
            // Scale by luminance rather than mapping channels independently to retain hue.
            val scale = (1.0 + luminance / 100.0) / (1.0 + luminance)
            output[dst] = display(r * scale, displayLut)
            output[dst + 1] = display(g * scale, displayLut)
            output[dst + 2] = display(b * scale, displayLut)
            src += 6; dst += 3
        }
    }

    private fun sample(input: ByteArray, offset: Int) =
        (input[offset].toInt() and 255) or ((input[offset + 1].toInt() and 255) shl 8)

    private fun display(linear: Double, lut: ByteArray) = lut[(linear.coerceIn(0.0, 1.0) * 65535).roundToInt()]

    companion object {
        private val pq by lazy {
            DoubleArray(65536) { i ->
                val p = (i / 65535.0).pow(1.0 / 78.84375)
                // ST 2084 EOTF, normalized to 100 nits (PQ's peak is 10,000 nits).
                100.0 * (max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p)).pow(1.0 / 0.1593017578125)
            }
        }
        private val hlg by lazy {
            DoubleArray(65536) { i ->
                val e = i / 65535.0
                if (e <= 0.5) e * e / 3.0 else (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
            }
        }
        private val hlgGain by lazy { DoubleArray(65536) { (it / 65535.0).pow(0.2) } }
        private val srgb by lazy {
            ByteArray(65536) { i ->
                val linear = i / 65535.0
                val encoded = if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1.0 / 2.4) - 0.055
                (encoded * 255).roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }
}
