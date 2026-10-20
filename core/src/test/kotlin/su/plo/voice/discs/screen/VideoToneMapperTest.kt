package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class VideoToneMapperTest {
    private fun frame(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, value ->
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
    }
    private fun pq(nits: Double): Int {
        val p = (nits / 10_000).pow(0.1593017578125)
        return (((0.8359375 + 18.8515625 * p) / (1 + 18.6875 * p)).pow(78.84375) * 65535).roundToInt()
    }
    private fun convert(info: VideoColorInfo, vararg samples: Int): List<Int> {
        val output = ByteArray(samples.size)
        VideoToneMapper(info).convert(frame(*samples), output)
        return output.map { it.toInt() and 255 }
    }

    @Test fun `only input video metadata selects PQ or HLG`() {
        assertNull(VideoColorInfo.fromStreamLine("Input #0, matroska, from 'smpte2084.mkv':"))
        assertNull(VideoColorInfo.fromStreamLine("  Stream #0:1: Audio: aac"))
        assertEquals(VideoColorInfo(VideoTransfer.PQ, true), VideoColorInfo.fromStreamLine(
            "  Stream #0:0(eng): Video: hevc (Main 10), yuv420p10le(tv, bt2020nc/bt2020/smpte2084), 1920x1080"))
        assertEquals(VideoColorInfo(VideoTransfer.HLG, true), VideoColorInfo.fromStreamLine(
            "  Stream #0:2: Video: vp9, yuv420p10le(tv, bt2020nc/bt2020/arib-std-b67), 1920x1080"))
        assertEquals(VideoColorInfo(), VideoColorInfo.fromStreamLine(
            "  Stream #0:0: Video: h264, yuv420p(tv, bt709), 1920x1080"))
    }

    @Test fun `all eight bit SDR values survive the higher precision decode path unchanged`() {
        for (value in 0..255) {
            assertEquals(listOf(value, value, value), convert(VideoColorInfo(), value * 256, value * 256, value * 256))
        }
    }

    @Test fun `PQ black stays black and diffuse whites and highlights retain neutral color and order`() {
        val info = VideoColorInfo(VideoTransfer.PQ, true)
        var previous = -1
        for (nits in listOf(0.0, 0.1, 1.0, 10.0, 100.0, 203.0, 400.0, 1000.0)) {
            val value = pq(nits)
            val result = convert(info, value, value, value)
            assertTrue(result.max() - result.min() <= 1, "Neutral HDR must stay neutral")
            assertTrue(result.first() > previous, "Luminance must increase at $nits nits")
            previous = result.first()
        }
        assertEquals(listOf(0, 0, 0), convert(info, 0, 0, 0))
        assertEquals(255, previous)
        val white = pq(203.0)
        assertTrue(convert(info, white, white, white).first() in 210..225)
    }

    @Test fun `HLG preserves blacks and produces neutral SDR white on its reference display`() {
        val info = VideoColorInfo(VideoTransfer.HLG, true)
        assertEquals(listOf(0, 0, 0), convert(info, 0, 0, 0))
        assertEquals(listOf(255, 255, 255), convert(info, 65535, 65535, 65535))
        val diffuseWhite = (0.75 * 65535).roundToInt()
        assertTrue(convert(info, diffuseWhite, diffuseWhite, diffuseWhite).first() in 210..225)
    }

    @Test fun `gamut conversion keeps saturated channel ordering without wrapping negative values`() {
        val level = pq(100.0)
        for ((pixel, dominant) in listOf(intArrayOf(level, 0, 0) to 0,
            intArrayOf(0, level, 0) to 1, intArrayOf(0, 0, level) to 2)) {
            val mapped = convert(VideoColorInfo(VideoTransfer.PQ, true), *pixel)
            assertEquals(mapped.max(), mapped[dominant])
            assertTrue(mapped.filterIndexed { index, _ -> index != dominant }.all { it == 0 })
        }
    }
}
