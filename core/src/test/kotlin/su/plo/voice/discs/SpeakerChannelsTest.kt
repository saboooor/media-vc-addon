package su.plo.voice.discs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpeakerChannelsTest {
    @Test fun `speakers receive distinct original channels with the requested frame bounds`() {
        for (channel in 0..1) {
            val input = arrayOf(floatArrayOf(9f, 1f, 2f, 9f), floatArrayOf(8f, 3f, 4f, 8f))
            SpeakerChannels.isolate(input, 1, 2, channel)
            val expected = if (channel == 0) floatArrayOf(1f, 2f) else floatArrayOf(3f, 4f)
            input.forEach { assertArrayEquals(expected, it.copyOfRange(1, 3)) }
            assertEquals(9f, input[0][0])
            assertEquals(8f, input[1][3])
        }
    }

    @Test fun `mono recordings remain audible from either speaker`() {
        val mono = arrayOf(floatArrayOf(1f, 2f))
        SpeakerChannels.isolate(mono, 0, 2, 1)
        assertArrayEquals(floatArrayOf(1f, 2f), mono[0])
    }
}
