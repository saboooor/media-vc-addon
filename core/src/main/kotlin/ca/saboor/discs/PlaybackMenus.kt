package ca.saboor.discs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Identical playback submenus for placed and handheld jukeboxes. */
internal object PlaybackMenus {
    data class Page(val title: String, val body: List<Component>, val buttons: List<Pair<String, String>>)
    fun volume(prefix: String, volume: Int, playing: Boolean): Page {
        val body = mutableListOf<Component>()
        val buttons = mutableListOf<Pair<String, String>>()
            body += Component.text("Volume: $volume%", NamedTextColor.GOLD)
            body += Component.text(if (!playing) "Applies when playback starts." else "Changes apply immediately.", NamedTextColor.GRAY)
            for (step in listOf(1, 5, 10, 25)) {
                buttons += "Volume −$step%" to (prefix + "volume_minus_$step")
                buttons += "Volume +$step%" to (prefix + "volume_plus_$step")
            }
            for (preset in listOf(0, 10, 25, 50, 75, 100)) {
                val label = when (preset) { 0 -> "Mute"; 100 -> "Maximum · 100%"; else -> "Set $preset%" }
                buttons += label to (prefix + "volume_set_$preset")
            }
            buttons += "Back to playback" to (prefix + "refresh")
        return Page("Volume", body, buttons)
    }
    fun effects(prefix: String, cfg: ca.saboor.discs.audio.AudioFilterConfig): Page {
        val eqName = cfg.eqPreset?.let { ca.saboor.discs.audio.EqPreset.fromId(it)?.label } ?: "Flat / Off"
        val body = mutableListOf<Component>()
        val buttons = mutableListOf<Pair<String, String>>()
            body += Component.text("Audio Effects & Equalizer", NamedTextColor.GOLD)
            body += Component.text("Tempo: ${"%.2f".format(cfg.tempo)}x · Pitch: ${cfg.pitchSemitones} semitones", NamedTextColor.GRAY)
            body += Component.text("Equalizer preset: $eqName", NamedTextColor.YELLOW)
            val activeList = mutableListOf<String>()
            if (cfg.lowPassHz > 0) activeList += "Low-pass ${cfg.lowPassHz.toInt()} Hz"
            if (cfg.highPassHz > 0) activeList += "High-pass ${cfg.highPassHz.toInt()} Hz"
            if (cfg.rotation) activeList += "8D Audio"
            if (cfg.echo) activeList += "Echo"
            if (cfg.underwater) activeList += "Underwater"
            if (cfg.tremolo) activeList += "Tremolo"
            if (cfg.vibrato) activeList += "Vibrato"
            if (cfg.speed == 1.25) activeList += "Nightcore (1.25x)"
            else if (cfg.speed == 0.8) activeList += "Vaporwave (0.8x)"
            else if (cfg.speed != 1.0) activeList += "Speed (${cfg.speed}x)"

            if (activeList.isNotEmpty()) {
                body += Component.text("Active DSP: ${activeList.joinToString(", ")}", NamedTextColor.AQUA)
            } else {
                body += Component.text("No audio effects active.", NamedTextColor.GRAY)
            }

            buttons += "Low-pass: ${if (cfg.lowPassHz == 0.0) "Off" else "${cfg.lowPassHz.toInt()} Hz"} (cycle)" to (prefix + "fx_lowpass")
            buttons += "High-pass: ${if (cfg.highPassHz == 0.0) "Off" else "${cfg.highPassHz.toInt()} Hz"} (cycle)" to (prefix + "fx_highpass")
            buttons += "Equalizer presets" to (prefix + "fx_eq_menu")
            buttons += "${if (cfg.rotation) "✓ " else ""}8D Audio" to (prefix + "fx_toggle_8d")
            buttons += "${if (cfg.echo) "✓ " else ""}Echo" to (prefix + "fx_toggle_echo")
            buttons += "${if (cfg.underwater) "✓ " else ""}Underwater" to (prefix + "fx_toggle_underwater")
            buttons += "${if (cfg.tremolo) "✓ " else ""}Tremolo" to (prefix + "fx_toggle_tremolo")
            buttons += "${if (cfg.vibrato) "✓ " else ""}Vibrato" to (prefix + "fx_toggle_vibrato")
            buttons += "Tempo -5%" to (prefix + "fx_tempo_down")
            buttons += "Tempo +5%" to (prefix + "fx_tempo_up")
            buttons += "Pitch -1 semitone" to (prefix + "fx_pitch_down")
            buttons += "Pitch +1 semitone" to (prefix + "fx_pitch_up")
            buttons += "Reset tempo & pitch" to (prefix + "fx_timescale_reset")
            buttons += "${if (cfg.speed == 1.25) "✓ " else ""}Nightcore" to (prefix + "fx_toggle_nightcore")
            buttons += "${if (cfg.speed == 0.8) "✓ " else ""}Vaporwave" to (prefix + "fx_toggle_vaporwave")
            buttons += "Reset all effects" to (prefix + "fx_clear")
            buttons += "Back to playback" to (prefix + "refresh")
        return Page("Audio effects", body, buttons)
    }
    fun equalizer(prefix: String, cfg: ca.saboor.discs.audio.AudioFilterConfig): Page {
        val body = mutableListOf<Component>()
        val buttons = mutableListOf<Pair<String, String>>()
            body += Component.text("Select Equalizer Preset", NamedTextColor.GOLD)
            for (preset in ca.saboor.discs.audio.EqPreset.entries) {
                val selected = (cfg.eqPreset == null && preset == ca.saboor.discs.audio.EqPreset.FLAT) ||
                    (cfg.eqPreset != null && preset == ca.saboor.discs.audio.EqPreset.fromId(cfg.eqPreset))
                buttons += "${if (selected) "▶ " else ""}${preset.label}" to (prefix + "fx_set_eq_${preset.id}")
            }
            buttons += "Back to effects" to (prefix + "effects_menu")
            buttons += "Back to playback" to (prefix + "refresh")
        return Page("Equalizer", body, buttons)
    }
}
