package ca.saboor.discs.command.subcommand

import ca.saboor.discs.event.JukeboxProgressDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.utils.extend.getValue
import su.plo.voice.discs.utils.extend.getterOrNull

class HologramCommand(
    private val jukeboxes: JukeboxEventListener,
    private val controlCommand: ControlCommand,
    private val editor: VisualizerCommand,
) : SubCommand() {

    private val progressDisplay: JukeboxProgressDisplay? by getterOrNull<JukeboxProgressDisplay>()

    override val name = "hologram"
    override val permissions = listOf("control" to PermissionDefault.TRUE)

    override fun checkCanExecute(sender: CommandSender): Boolean =
        (sender.hasPermission("pv.addon.discs.control") || sender.hasPermission("vc.addon.discs.control")) &&
            (sender.hasPermission("pv.addon.discs.play") || sender.hasPermission("vc.addon.discs.play"))

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
            return
        }
        if (!checkCanExecute(player)) return
        if (arguments.getOrNull(1).equals("done", ignoreCase = true)) {
            editor.finishEditing(player)
            return
        }
        val display = progressDisplay ?: run {
            player.sendMessage(Component.text("Holograms are not available on this server version.", NamedTextColor.RED))
            return
        }

        val targetJukebox = findTargetJukebox(player) ?: run {
            player.sendMessage(Component.text("Look at a jukebox (or chest above a jukebox) within 6 blocks.", NamedTextColor.RED))
            return
        }

        if (arguments.size <= 1 || arguments[1].equals("edit", ignoreCase = true)) {
            editor.editHologram(player, targetJukebox)
            return
        }
        if (arguments[1].equals("gui", ignoreCase = true)) {
            controlCommand.showHologramDialog(player, targetJukebox)
            return
        }

        when (arguments[1].lowercase()) {
            "move" -> {
                if (arguments.size < 3) {
                    player.sendMessage(Component.text("Usage: /disc hologram move <up|down|north|south|east|west> [amount]", NamedTextColor.RED))
                    return
                }
                val dir = arguments[2].lowercase()
                val amount = arguments.getOrNull(3)?.toDoubleOrNull() ?: 0.25
                val (dx, dy, dz) = when (dir) {
                    "up" -> Triple(0.0, amount, 0.0)
                    "down" -> Triple(0.0, -amount, 0.0)
                    "north" -> Triple(0.0, 0.0, -amount)
                    "south" -> Triple(0.0, 0.0, amount)
                    "east" -> Triple(amount, 0.0, 0.0)
                    "west" -> Triple(-amount, 0.0, 0.0)
                    else -> {
                        player.sendMessage(Component.text("Unknown direction '$dir'. Use up, down, north, south, east, west.", NamedTextColor.RED))
                        return
                    }
                }
                val newOffset = display.adjustOffset(targetJukebox, dx, dy, dz)
                player.sendMessage(Component.text("Hologram moved. Current offset: X: ${newOffset.x}, Y: ${newOffset.y}, Z: ${newOffset.z}", NamedTextColor.GREEN))
            }
            "offset" -> {
                if (arguments.size < 5) {
                    player.sendMessage(Component.text("Usage: /disc hologram offset <x> <y> <z>", NamedTextColor.RED))
                    return
                }
                val x = arguments[2].toDoubleOrNull()
                val y = arguments[3].toDoubleOrNull()
                val z = arguments[4].toDoubleOrNull()
                if (x == null || y == null || z == null) {
                    player.sendMessage(Component.text("Invalid offset values. Usage: /disc hologram offset <x> <y> <z>", NamedTextColor.RED))
                    return
                }
                display.setOffset(targetJukebox, x, y, z)
                val newOffset = display.getOffset(targetJukebox)
                player.sendMessage(Component.text("Hologram offset set to X: ${newOffset.x}, Y: ${newOffset.y}, Z: ${newOffset.z}", NamedTextColor.GREEN))
            }
            "reset" -> {
                display.resetOffset(targetJukebox)
                player.sendMessage(Component.text("Hologram position reset to default.", NamedTextColor.GREEN))
            }
            else -> {
                player.sendMessage(Component.text("Usage: /disc hologram [edit|done|gui|move|offset|reset]", NamedTextColor.RED))
            }
        }
    }

    private fun findTargetJukebox(player: Player): Block? {
        val target = player.getTargetBlockExact(6)
        if (target != null) {
            if (target.type == Material.JUKEBOX) return target
            val below = target.getRelative(BlockFace.DOWN)
            if (below.type == Material.JUKEBOX) return below
            val chestState = target.state as? Chest
            val doubleChest = chestState?.inventory?.holder as? DoubleChest
            val leftJukebox = (doubleChest?.leftSide as? Chest)?.block?.getRelative(BlockFace.DOWN)
            if (leftJukebox?.type == Material.JUKEBOX) return leftJukebox
            val rightJukebox = (doubleChest?.rightSide as? Chest)?.block?.getRelative(BlockFace.DOWN)
            if (rightJukebox?.type == Material.JUKEBOX) return rightJukebox
        }

        // Fallback: check if there's any active jukebox within 8 blocks of player
        return jukeboxes.controlsByBlock.keys.firstOrNull {
            it.world == player.world && it.location.distanceSquared(player.location) <= 64
        }
    }

    override fun suggest(sender: CommandSender, arguments: Array<out String>): List<String> {
        return when (arguments.size) {
            2 -> listOf("edit", "done", "gui", "move", "offset", "reset").filter { it.startsWith(arguments[1], ignoreCase = true) }
            3 -> if (arguments[1].equals("move", ignoreCase = true)) {
                listOf("up", "down", "north", "south", "east", "west").filter { it.startsWith(arguments[2], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }
}

