package ca.saboor.discs.screen

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class CompanionResolutionTest {
    @Test fun `resolution fits the screen aspect ratio inside the selected box`() {
        assertEquals(CompanionResolution.Size(512, 384), CompanionResolution.size(512, 384, "map"))
        assertEquals(CompanionResolution.Size(1440, 1080), CompanionResolution.size(512, 384, "1080p"))
        assertEquals(CompanionResolution.Size(960, 720), CompanionResolution.size(512, 384, "720p"))
        assertEquals(CompanionResolution.Size(2880, 2160), CompanionResolution.size(512, 384, "source"))
        assertEquals(CompanionResolution.Size(512, 384), CompanionResolution.size(512, 384, "invalid"))
    }

    @Test fun `vanilla fallback is resized independently with blended RGB pixels`() {
        val source = byteArrayOf(0, 0, 0, -1, -1, -1, -1, -1, -1, 0, 0, 0)
        val scaler = RgbFrameScaler()
        val resized = scaler.scale(source, 2, 2, 1, 1)
        assertArrayEquals(byteArrayOf(-128, -128, -128), resized)
        assertArrayEquals(byteArrayOf(0, 0, 0, -1, -1, -1, -1, -1, -1, 0, 0, 0), source)
        assertSame(source, scaler.scale(source, 2, 2, 2, 2))
    }

    @Test fun `output metadata rejects oversized raw buffers`() {
        assertEquals(CompanionResolution.Size(1920, 1080), CompanionResolution.parseOutputSize("[Parsed_showinfo_2] n: 0 s:1920x1080 i:P"))
        assertNull(CompanionResolution.parseOutputSize("Stream #0:0 Video: 1920x1080"))
        assertThrows(IllegalArgumentException::class.java) {
            CompanionResolution.parseOutputSize("[showinfo] s:8192x8192")
        }
    }

    @Test fun `HD filter preserves native detail and letterboxes without upscaling`() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0 }.getOrDefault(false))
        for ((source, target, expected) in listOf(
            Triple("64x36", CompanionResolution.Size(1920, 1080), CompanionResolution.Size(64, 36)),
            Triple("64x64", CompanionResolution.Size(1920, 1080), CompanionResolution.Size(114, 64)),
            Triple("1920x1080", CompanionResolution.Size(1280, 720), CompanionResolution.Size(1280, 720))
        )) {
            val log = Files.createTempFile("companion-size", ".log").toFile()
            val filter = VideoPlayer.videoFilter(30, target.width, target.height, true).removeSuffix(",realtime")
            val process = ProcessBuilder("ffmpeg", "-hide_banner", "-f", "lavfi", "-i", "testsrc=size=$source:rate=30",
                "-vf", "$filter,showinfo=checksum=0", "-frames:v", "1", "-threads:v", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb48le", "pipe:1").redirectError(log).start()
            try {
                val bytes = process.inputStream.use { it.readBytes() }
                assertEquals(0, process.waitFor(), log.readText())
                assertEquals(expected, log.readLines().firstNotNullOfOrNull(CompanionResolution::parseOutputSize))
                assertEquals(expected.width * expected.height * 6, bytes.size)
            } finally { process.destroyForcibly(); log.delete() }
        }
    }
}
