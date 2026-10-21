package su.plo.voice.discs.command.subcommand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.koin.core.component.inject
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.audio.AudioFilterConfig
import su.plo.voice.discs.audio.EqPreset
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.utils.extend.asPlayer

class EffectCommand : SubCommand() {

    override val name: String = "effect"

    override val permissions: List<Pair<String, PermissionDefault>> = listOf(
        "effect" to PermissionDefault.TRUE
    )

    private val jukeboxes: JukeboxEventListener by inject()
    private val controlCommand: ControlCommand by inject()

    private val validOptions = listOf(
        "gui", "clear", "tempo", "pitch", "lowpass", "highpass", "8d", "echo", "underwater", "tremolo", "vibrato", "nightcore", "vaporwave"
    ) + EqPreset.entries.map { it.id }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender.asPlayer() ?: run {
            sender.sendMessage(Component.text("This command can only be run by players.", NamedTextColor.RED))
            return
        }

        if (!controlCommand.checkCanExecute(player)) {
            player.sendMessage(Component.text("No permission to control this jukebox.", NamedTextColor.RED))
            return
        }
        val targetBlock = player.getTargetBlockExact(8)
        val jukeboxBlock = if (targetBlock?.type == Material.JUKEBOX) {
            targetBlock
        } else {
            // Find jukebox nearby
            player.location.block.let { loc ->
                (-3..3).flatMap { x ->
                    (-2..2).flatMap { y ->
                        (-3..3).map { z -> loc.getRelative(x, y, z) }
                    }
                }.firstOrNull { it.type == Material.JUKEBOX && jukeboxes.isPlaying(it) }
            }
        }

        if (jukeboxBlock == null) {
            player.sendMessage(Component.text("Look at a jukebox within 8 blocks to adjust audio effects.", NamedTextColor.RED))
            return
        }

        val action = arguments.getOrNull(1)?.lowercase() ?: "gui"
        if (action == "gui") {
            controlCommand.show(player, jukeboxBlock, effectsMenu = true)
            return
        }

        if (action == "clear" || action == "reset") {
            jukeboxes.setFilterConfig(jukeboxBlock, AudioFilterConfig())
            player.sendMessage(Component.text("Cleared all audio effects and equalizer settings.", NamedTextColor.GREEN))
            return
        }

        val current = jukeboxes.filterConfig(jukeboxBlock)
        if (action == "tempo" || action == "pitch") {
            val value = arguments.getOrNull(2)?.toDoubleOrNull()
            val valid = value != null && value.isFinite() &&
                (if (action == "tempo") value in 0.5..2.0 else value in -12.0..12.0)
            if (!valid || arguments.size != 3) {
                player.sendMessage(Component.text(if (action == "tempo") "Usage: /disc effect tempo <0.5-2.0> (preserves pitch)"
                    else "Usage: /disc effect pitch <-12 to 12 semitones> (preserves tempo)", NamedTextColor.RED))
                return
            }
            val updated = if (action == "tempo") current.copy(tempo = value!!) else current.copy(pitchSemitones = value!!)
            jukeboxes.setFilterConfig(jukeboxBlock, updated)
            player.sendMessage(Component.text(if (action == "tempo") "Tempo set to ${value}x; pitch preserved."
                else "Pitch set to $value semitones; tempo preserved.", NamedTextColor.GREEN))
            return
        }
        if (action == "lowpass" || action == "highpass") {
            val value = if (arguments.getOrNull(2).equals("off", true)) 0.0 else arguments.getOrNull(2)?.toDoubleOrNull()
            if (arguments.size != 3 || value == null || !value.isFinite() || (value != 0.0 && value !in 20.0..20000.0)) {
                player.sendMessage(Component.text("Usage: /disc effect $action <20-20000 Hz|off>", NamedTextColor.RED))
                return
            }
            jukeboxes.setFilterConfig(jukeboxBlock, if (action == "lowpass") current.copy(lowPassHz = value) else current.copy(highPassHz = value))
            player.sendMessage(Component.text("$action: ${if (value == 0.0) "Off" else "$value Hz"}", NamedTextColor.GREEN))
            return
        }
        val preset = EqPreset.fromId(action)
        if (preset != null) {
            val updated = if (preset == EqPreset.FLAT) current.copy(eqPreset = null, bands = null)
            else current.copy(eqPreset = preset.id, bands = null)
            jukeboxes.setFilterConfig(jukeboxBlock, updated)
            player.sendMessage(Component.text("Equalizer preset set to: ${preset.label}", NamedTextColor.GREEN))
            return
        }

        val updated = when (action) {
            "8d", "rotation", "pan" -> current.copy(rotation = !current.rotation).also {
                player.sendMessage(Component.text("8D Audio ${if (it.rotation) "enabled" else "disabled"}.", NamedTextColor.GREEN))
            }
            "echo" -> current.copy(echo = !current.echo).also {
                player.sendMessage(Component.text("Echo effect ${if (it.echo) "enabled" else "disabled"}.", NamedTextColor.GREEN))
            }
            "underwater" -> current.copy(underwater = !current.underwater).also {
                player.sendMessage(Component.text("Underwater effect ${if (it.underwater) "enabled" else "disabled"}.", NamedTextColor.GREEN))
            }
            "tremolo" -> current.copy(tremolo = !current.tremolo).also {
                player.sendMessage(Component.text("Tremolo effect ${if (it.tremolo) "enabled" else "disabled"}.", NamedTextColor.GREEN))
            }
            "vibrato", "cry" -> current.copy(vibrato = !current.vibrato).also {
                player.sendMessage(Component.text("Vibrato effect ${if (it.vibrato) "enabled" else "disabled"}.", NamedTextColor.GREEN))
            }
            "nightcore" -> {
                val next = if (current.speed == 1.25) 1.0 else 1.25
                current.copy(speed = next).also {
                    player.sendMessage(Component.text("Nightcore (1.25x) ${if (it.speed == 1.25) "enabled" else "disabled"}.", NamedTextColor.GREEN))
                }
            }
            "vaporwave" -> {
                val next = if (current.speed == 0.8) 1.0 else 0.8
                current.copy(speed = next).also {
                    player.sendMessage(Component.text("Vaporwave (0.8x) ${if (it.speed == 0.8) "enabled" else "disabled"}.", NamedTextColor.GREEN))
                }
            }
            else -> {
                player.sendMessage(Component.text("Unknown effect. Available: ${validOptions.joinToString(", ")}", NamedTextColor.RED))
                return
            }
        }

        jukeboxes.setFilterConfig(jukeboxBlock, updated)
    }

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {
        if (arguments.size == 3 && arguments[1] == "tempo") return listOf("0.8", "1.0", "1.25")
        if (arguments.size == 3 && arguments[1] == "pitch") return listOf("-12", "-2", "0", "2", "12")
        if (arguments.size == 2) {
            return validOptions.filter { it.startsWith(arguments[1].lowercase()) }
        }
        return emptyList()
    }
}


