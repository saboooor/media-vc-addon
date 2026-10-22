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

    @Test fun `lamp menu includes unset all lights and show hide lights button when lights are linked`() {
        val buttons = mutableListOf<Pair<String, String>>()
        ca.saboor.discs.command.subcommand.ControlCommand.bodyLampButtons(buttons, "/disc control token ", 0)
        assertTrue(buttons.any { it.first == "Falloff settings" && it.second.endsWith("lamp_falloff_menu") })
        assertFalse(buttons.any { it.first == "Unset all lights" })
        assertFalse(buttons.any { it.second.endsWith("lamp_unset_all") })
        assertFalse(buttons.any { it.first == "Show lights" })
        assertFalse(buttons.any { it.first == "Hide lights" })
        assertFalse(buttons.any { it.second.endsWith("lamp_show") })
        assertFalse(buttons.any { it.first.startsWith("Unlink light") })

        buttons.clear()
        ca.saboor.discs.command.subcommand.ControlCommand.bodyLampButtons(buttons, "/disc control token ", 3, isShowing = false)
        assertTrue(buttons.any { it.first == "Show lights" })
        assertTrue(buttons.any { it.second.endsWith("lamp_show") })
        assertTrue(buttons.any { it.first == "Unset all lights" })
        assertTrue(buttons.any { it.second.endsWith("lamp_unset_all") })
        assertFalse(buttons.any { it.first.startsWith("Unlink light") })
        assertFalse(buttons.any { it.second.endsWith("lamp_unlink") })

        buttons.clear()
        ca.saboor.discs.command.subcommand.ControlCommand.bodyLampButtons(buttons, "/disc control token ", 3, isShowing = true)
        assertTrue(buttons.any { it.first == "Hide lights" })
        assertTrue(buttons.any { it.second.endsWith("lamp_show") })
    }

    @Test fun `lamp link identifies block type and band type`() {
        val pos = ca.saboor.discs.event.JukeboxBassLamps.Position("00000000-0000-0000-0000-000000000001", 10, 64, 20)
        val jukePos = ca.saboor.discs.event.JukeboxBassLamps.Position("00000000-0000-0000-0000-000000000001", 10, 64, 15)
        val lamp = ca.saboor.discs.event.JukeboxBassLamps.Link(pos, jukePos, ca.saboor.discs.visualizer.LightBand.SPARKLE, "minecraft:sea_lantern")
        assertEquals("Sea Lantern", lamp.blockTypeName)
        assertEquals("Sparkle", lamp.band?.label)

        val beacon = ca.saboor.discs.event.JukeboxBassLamps.Link(pos, jukePos, ca.saboor.discs.visualizer.LightBand.DEEP_BASS, "minecraft:beacon")
        assertEquals("Beacon", beacon.blockTypeName)
        assertEquals("Deep bass", beacon.band?.label)

        val defaultLamp = ca.saboor.discs.event.JukeboxBassLamps.Link(pos, jukePos, null, null)
        assertEquals("Redstone Lamp", defaultLamp.blockTypeName)
        assertEquals("Bass", (defaultLamp.band ?: ca.saboor.discs.visualizer.LightBand.BASS).label)
    }

    @Test fun `lamp menu includes configure lights button wave and chase toggles and distinct dye mappings`() {
        val buttons = mutableListOf<Pair<String, String>>()
        ca.saboor.discs.command.subcommand.ControlCommand.bodyLampButtons(
            buttons, "/disc control token ", 2, isShowing = false, waveMode = true, chaseMode = true
        )
        assertTrue(buttons.any { it.first == "Configure lights" && it.second.endsWith("lamp_link") })
        assertTrue(buttons.any { it.first == "✓ Wave ripple mode" && it.second.endsWith("lamp_wave") })
        assertTrue(buttons.any { it.first == "✓ Beat chase mode" && it.second.endsWith("lamp_chase") })

        val dyeMaterials = ca.saboor.discs.visualizer.LightBand.entries.map { band ->
            ca.saboor.discs.event.JukeboxBassLamps.bandDyeMaterial(band)
        }
        assertEquals(ca.saboor.discs.visualizer.LightBand.entries.size, dyeMaterials.distinct().size, "Each light band should have a distinct dye item")
        for (band in ca.saboor.discs.visualizer.LightBand.entries) {
            assertEquals(band, ca.saboor.discs.event.JukeboxBassLamps.bandFromDyeMaterial(ca.saboor.discs.event.JukeboxBassLamps.bandDyeMaterial(band)))
        }
        assertTrue(ca.saboor.discs.visualizer.LightBlock.supportedBlockNames.contains("Wool (color-changing)"))
    }
}
