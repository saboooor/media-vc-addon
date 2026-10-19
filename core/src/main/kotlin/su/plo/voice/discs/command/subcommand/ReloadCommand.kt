package su.plo.voice.discs.command.subcommand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.command.SubCommand

class ReloadCommand(private val reload: () -> Unit) : SubCommand() {

    override val name = "reload"

    override val permissions = listOf("reload" to PermissionDefault.OP)

    override fun checkCanExecute(sender: CommandSender): Boolean =
        sender.hasPermission("pv.addon.discs.reload") || sender.hasPermission("vc.addon.discs.reload")

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        if (!checkCanExecute(sender)) {
            sender.sendMessage(Component.text("No permission", NamedTextColor.RED))
            return
        }

        if (arguments.size != 1) {
            sender.sendMessage(Component.text("Usage: /disc reload", NamedTextColor.RED))
            return
        }

        try {
            reload()
            sender.sendMessage(Component.text(
                "Discs runtime config reloaded. Source line, proxy, YouTube, local source and recipe settings require a restart. Existing playback keeps its current settings.",
                NamedTextColor.GREEN
            ))
        } catch (e: Exception) {
            plugin.slF4JLogger.error("Failed to reload discs config", e)
            sender.sendMessage(Component.text("Failed to reload discs config. Check the console for details.", NamedTextColor.RED))
        }
    }
}
