package ca.saboor.discs

import ca.saboor.discs.audio.AudioFilterConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaybackMenusTest {
    @Test fun `portable and placed volume menus differ only in callback routing`() {
        val placed = PlaybackMenus.volume("/disc control token ", 40, true)
        val portable = PlaybackMenus.volume("/disc portable token ", 40, true)
        assertEquals(placed.title, portable.title)
        assertEquals(placed.body, portable.body)
        assertEquals(placed.buttons.map { it.first }, portable.buttons.map { it.first })
        assertTrue(portable.buttons.any { it.second.endsWith("volume_set_100") })
        assertTrue(portable.buttons.last().second.endsWith("refresh"))
    }

    @Test fun `effects and equalizer expose shared controls`() {
        val page = PlaybackMenus.effects("portable ", AudioFilterConfig(echo = true, tempo = 1.2))
        assertTrue(page.buttons.any { it.second == "portable fx_toggle_echo" })
        assertTrue(page.buttons.any { it.second == "portable fx_pitch_up" })
        assertTrue(page.buttons.any { it.second == "portable fx_eq_menu" })
        val eq = PlaybackMenus.equalizer("portable ", AudioFilterConfig())
        assertTrue(eq.buttons.any { it.second.startsWith("portable fx_set_eq_") })
    }
}
