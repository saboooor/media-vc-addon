package ca.saboor.discs.command.subcommand

import ca.saboor.discs.HandheldJukeboxes
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import su.plo.voice.discs.command.SubCommand

/** Short-lived dialog callbacks are bound to the player and the device still held. */
class PortableCommand(private val handheld: HandheldJukeboxes) : SubCommand() {
    override val name = "portable"
    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: return
        if (arguments.size == 3) handheld.handleControl(player, arguments[1], arguments[2])
    }
}
