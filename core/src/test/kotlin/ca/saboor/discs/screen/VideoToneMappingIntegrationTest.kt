package ca.saboor.discs.screen

import java.nio.file.Path
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VideoToneMappingIntegrationTest {
    @TempDir lateinit var directory: Path

    private fun decode(source: String, pixelFormat: String): Pair<ByteArray, List<String>> {
        assumeTrue(runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false), "FFmpeg is required for the HDR integration test")
        val log = directory.resolve("ffmpeg.log").toFile()
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin",
            "-f", "lavfi", "-i", source, "-map", "0:v:0", "-frames:v", "1",
            "-vf", VideoPlayer.videoFilter(15, 16, 16), "-threads:v", "1",
            "-f", "rawvideo", "-pix_fmt", pixelFormat, "pipe:1")
            .redirectError(log).start()
        try {
            val bytes = process.inputStream.use { it.readBytes() }
            assertEquals(0, process.waitFor(), log.readText())
            return bytes to log.readLines()
        } finally { process.destroyForcibly() }
    }

    @Test fun `decoded PQ metadata and sixteen bit samples produce neutral SDR highlights`() {
        fun pq(nits: Double): Int {
            val p = (nits / 10_000).pow(0.1593017578125)
            return (((0.8359375 + 18.8515625 * p) / (1 + 18.6875 * p)).pow(78.84375) * 65535).roundToInt()
        }
        val ramp = "if(lt(X,8),${pq(203.0)},${pq(1000.0)})"
        val (raw, lines) = decode("nullsrc=size=16x16:rate=1,format=gbrp16le," +
            "geq=r='$ramp':g='$ramp':b='$ramp'," +
            "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=gbr", "rgb48le")
        // Frame-level metadata may appear only in FFmpeg's output header, after input probing.
        val color = lines.mapNotNull(VideoColorInfo::fromStreamLine).last()
        assertEquals(VideoColorInfo(VideoTransfer.PQ, true), color)
        assertEquals(16 * 16 * 6, raw.size)
        val sdr = ByteArray(16 * 16 * 3)
        VideoToneMapper(color).convert(raw, sdr)
        assertTrue((sdr[0].toInt() and 255) in 210..225)
        assertEquals(255, sdr[8 * 3].toInt() and 255)
        for (pixel in 0 until 16 * 16) {
            val rgb = sdr.slice(pixel * 3 until pixel * 3 + 3).map { it.toInt() and 255 }
            assertTrue(rgb.max() - rgb.min() <= 1)
        }
    }

    @Test fun `SDR high precision decode preserves the previous RGB24 rendering`() {
        val source = "testsrc=size=16x16:rate=1"
        val old = decode(source, "rgb24").first
        val (raw, lines) = decode(source, "rgb48le")
        val color = lines.mapNotNull(VideoColorInfo::fromStreamLine).last()
        assertEquals(VideoTransfer.SDR, color.transfer)
        val converted = ByteArray(old.size)
        VideoToneMapper(color).convert(raw, converted)
        assertArrayEquals(old, converted)
    }
}
