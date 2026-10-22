package ca.saboor.discs.command.subcommand

import java.io.File
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.command.SubCommand

class OauthCommand : SubCommand() {

    override val name = "oauth"

    override val permissions = listOf("oauth" to PermissionDefault.OP)

    override fun checkCanExecute(sender: CommandSender): Boolean =
        sender.hasPermission("pv.addon.discs.oauth") || sender.hasPermission("vc.addon.discs.oauth")

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {
        if (!checkCanExecute(source)) return listOf()
        if (arguments.size == 2) {
            return listOf("force", "status").filter { it.startsWith(arguments[1], ignoreCase = true) }
        }
        return listOf()
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        if (!checkCanExecute(sender)) {
            sender.sendMessage(Component.text("No permission", NamedTextColor.RED))
            return
        }

        val tokenFile = File(plugin.dataFolder, ".youtube-token")
        val force = arguments.getOrNull(1).equals("force", ignoreCase = true)
        val status = arguments.getOrNull(1).equals("status", ignoreCase = true)

        if (status) {
            if (tokenFile.exists() && tokenFile.length() > 0) {
                sender.sendMessage(Component.text("YouTube OAuth2 is configured (refresh token present in .youtube-token).", NamedTextColor.GREEN))
            } else {
                sender.sendMessage(Component.text("YouTube OAuth2 is not authorized yet. Run '/disc oauth' to authorize.", NamedTextColor.YELLOW))
            }
            return
        }

        if (tokenFile.exists() && !force) {
            sender.sendMessage(Component.text(
                "YouTube OAuth2 token already exists (.youtube-token). Run '/disc oauth force' to generate a new token or re-authorize.",
                NamedTextColor.YELLOW
            ))
            return
        }

        val success = audioPlayerManager.startOauth2(force = force)
        if (!success) {
            sender.sendMessage(Component.text(
                "Failed to initiate YouTube OAuth2. Ensure YouTube source is enabled.",
                NamedTextColor.RED
            ))
            return
        }

        sender.sendMessage(Component.text("==================================================", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("YouTube OAuth2 Authorization Initiated!", NamedTextColor.YELLOW))
        sender.sendMessage(
            Component.text("Please visit ", NamedTextColor.WHITE)
                .append(
                    Component.text("https://www.google.com/device", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl("https://www.google.com/device"))
                )
                .append(Component.text(" and enter the pairing code displayed in the server console.", NamedTextColor.WHITE))
        )
        sender.sendMessage(Component.text("Note: Use a secondary/burner Google account rather than your primary personal account.", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("==================================================", NamedTextColor.GOLD))
    }
}

