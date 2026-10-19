package su.plo.voice.discs

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaybackDialogViewTest {
    @Test fun `icons switch atlases at Minecraft 1_21_11`() {
        assertEquals("minecraft:blocks", PlaybackDialogView.spriteAtlas("1.21.10"))
        assertEquals("minecraft:items", PlaybackDialogView.spriteAtlas("1.21.11"))
        assertEquals("minecraft:items", PlaybackDialogView.spriteAtlas("26.3"))
        assertEquals("minecraft:items", PlaybackDialogView.spriteAtlas("26.3-build17"))
    }

    @Test fun `progress fills proportionally and stays within its bounds`() {
        assertEquals(0, PlaybackDialogView.filledSegments(0, 100_000))
        assertEquals(12, PlaybackDialogView.filledSegments(50_000, 100_000))
        assertEquals(24, PlaybackDialogView.filledSegments(100_000, 100_000))
        assertEquals(24, PlaybackDialogView.filledSegments(Long.MAX_VALUE, 100_000))
        assertEquals(0, PlaybackDialogView.filledSegments(-10_000, 100_000))
        assertEquals(0, PlaybackDialogView.filledSegments(10_000, 0))
    }

    @Test fun `live streams and loading audio do not show a misleading percentage`() {
        val serializer = PlainTextComponentSerializer.plainText()
        val live = serializer.serialize(PlaybackDialogView.progress(65_000, Long.MAX_VALUE, true))
        assertEquals("● LIVE  ·  1:05 elapsed", live)
        assertFalse(live.contains("%"))
        assertEquals("Loading audio…", serializer.serialize(PlaybackDialogView.progress(0, 0, false)))
    }

    @Test fun `progress includes readable time and percentage for long tracks`() {
        val text = PlainTextComponentSerializer.plainText().serialize(PlaybackDialogView.progress(3_600_000, 7_200_000, false))
        assertTrue(text.contains("50%"))
        assertTrue(text.contains("1:00:00 / 2:00:00"))
        assertEquals(24, text.count { it == '━' })
    }
}
