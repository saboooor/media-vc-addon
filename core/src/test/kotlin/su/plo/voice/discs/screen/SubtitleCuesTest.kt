package su.plo.voice.discs.screen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubtitleCuesTest {
    @Test fun `multiline SRT captions have exact boundaries and support backwards seeks`() {
        val cues = SubtitleCues.parse("\uFEFF1\r\n00:00:01,250 --> 00:00:03,000\r\n<i>Hello</i>\r\nworld &amp; friends\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nNext")
        assertEquals("", cues.textAt(1249))
        assertEquals("Hello\nworld & friends", cues.textAt(1250))
        assertEquals("", cues.textAt(3000))
        assertEquals("Next", cues.textAt(4500))
        assertEquals("Hello\nworld & friends", cues.textAt(2000))
        assertEquals("Hello\nworld & friends", cues.textAt(2000)) // paused clock
    }
    @Test fun `VTT settings overlapping cues and malformed blocks are handled`() {
        val cues = SubtitleCues.parse("WEBVTT\n\na\n00:01.000 --> 00:03.000 align:center\n<v Speaker>One</v>\n\nb\n00:02.000 --> 00:04.000\nTwo\n\nbad\n00:99.000 --> 00:02.000\nInvalid")
        assertEquals("One\nTwo", cues.textAt(2000))
        assertEquals("Two", cues.textAt(3000))
        assertEquals("", cues.textAt(4000))
    }
}
