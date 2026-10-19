package su.plo.voice.discs.command.subcommand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.koin.core.component.inject
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.GoatHornManager
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.utils.HornCancelCommandCause
import su.plo.voice.discs.utils.extend.*

class CancelCommand : SubCommand() {

    private val hornManager: GoatHornManager by inject()

    override val name: String = "cancel"

    override val permissions = listOf(
        "cancel" to PermissionDefault.TRUE,
        "cancel.other" to PermissionDefault.OP,
    )

    override fun execute(sender: CommandSender, arguments: Array<out String>) = scope.launchAndForget {
        val player = sender.asPlayer() ?: return@launchAndForget
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }

        if (!player.hasPermission("pv.addon.discs.cancel") && !player.hasPermission("vc.addon.discs.cancel")) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.no_permission")
            } else {
                player.sendMessage(Component.text("You don't have permission to do this.", NamedTextColor.RED))
            }
            return@launchAndForget
        }

        val hasOtherPerm = player.hasPermission("pv.addon.discs.cancel.other") || player.hasPermission("vc.addon.discs.cancel.other")

        val target = arguments.getOrNull(1)
            ?.takeIf { hasOtherPerm }
            ?.let {
                Bukkit.getPlayer(it) ?: run {
                    if (voicePlayer != null) {
                        voicePlayer.instance.sendTranslatable("pv.addon.discs.error.player_not_found")
                    } else {
                        player.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    }
                    return@launchAndForget
                }
            }
            ?: player

        if (hornManager.cancelTrack(target, HornCancelCommandCause()) != null) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.success.canceled")
            } else {
                player.sendMessage(Component.text("Playback cancelled.", NamedTextColor.GREEN))
            }
        } else {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.nothing_to_cancel")
            } else {
                player.sendMessage(Component.text("Nothing to cancel.", NamedTextColor.RED))
            }
        }
    }

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {
        val player = source.asPlayer() ?: return emptyList()
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }
        val otherPermission = source.hasPermission("pv.addon.discs.cancel.other") || source.hasPermission("vc.addon.discs.cancel.other")

        val nick = arguments.getOrNull(1) ?: run {
            return if (otherPermission) {
                if (voicePlayer != null && voiceServer != null) {
                    listOf(
                        McTextComponent.translatable("pv.addon.discs.arg.nick")
                            .render(voicePlayer, voiceServer!!)
                            .toPlainText()
                    )
                } else {
                    listOf("<player>")
                }
            } else {
                emptyList()
            }
        }
        if (!otherPermission) return emptyList()

        return Bukkit.getOnlinePlayers()
            .map { it.name }
            .filter { it.startsWith(nick, ignoreCase = true) }
    }

    override fun checkCanExecute(sender: CommandSender): Boolean =
        sender.hasPermission("pv.addon.discs.cancel") || sender.hasPermission("vc.addon.discs.cancel")
}
