package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class VideoFrameRateTest {
    @Test fun `output preserves slower sources and caps faster sources including fractional rates`() {
        assumeTrue(runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false), "FFmpeg is required for the frame rate integration test")

        for ((sourceRate, cap, expectedFrames) in listOf(
            Triple("12", 30, 24),
            Triple("60", 15, 30),
            Triple("24000/1001", 30, 48)
        )) {
            val process = ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=16x16:rate=$sourceRate:duration=2",
                "-vf", VideoPlayer.videoFilter(cap, 16, 16),
                "-threads:v", "1", "-flush_packets", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try {
                val arrivals = mutableListOf<Long>()
                val bytes = process.inputStream.use { input ->
                    var total = 0
                    while (true) {
                        val frame = input.readNBytes(16 * 16 * 3)
                        if (frame.isEmpty()) break
                        arrivals.add(System.nanoTime())
                        total += frame.size
                    }
                    total
                }
                assertEquals(0, process.waitFor())
                assertEquals(expectedFrames * 16 * 16 * 3, bytes, "source=$sourceRate, cap=$cap")
                // Measure after the first frame so process startup cannot mask unpaced output.
                assertTrue(arrivals.last() - arrivals.first() >= 1_500_000_000L,
                    "Two seconds of video must be delivered in real time, source=$sourceRate")
            } finally {
                process.destroyForcibly()
            }
        }
    }
}
