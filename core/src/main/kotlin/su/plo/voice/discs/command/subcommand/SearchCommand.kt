package su.plo.voice.discs.command.subcommand

import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.chat.style.McTextClickEvent
import su.plo.slib.api.chat.style.McTextHoverEvent
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.api.PlayerSearchEvent
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.utils.extend.*

class SearchCommand : SubCommand() {

    override val name = "search"

    override val permissions = listOf(
        "search" to PermissionDefault.OP
    )

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {

        val player = source.asPlayer() ?: return listOf()
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }

        if (!player.hasPermission("pv.addon.discs.search") && !player.hasPermission("vc.addon.discs.search")) return listOf()

        return if (voicePlayer != null && voiceServer != null) {
            listOf(
                McTextComponent.translatable("pv.addon.discs.arg.search")
                    .render(voicePlayer, voiceServer!!)
                    .toPlainText()
            )
        } else {
            listOf("<search>")
        }
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) { scope.launch {

        val player = sender.asPlayer() ?: return@launch
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }

        if (!player.hasPermission("pv.addon.discs.search") && !player.hasPermission("vc.addon.discs.search")) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.no_permission")
            } else {
                player.sendMessage(Component.text("No permission", NamedTextColor.RED))
            }
            return@launch
        }

        val query = arguments.drop(1).joinToString(" ")

        if (voicePlayer != null) {
            if (!suspendSyncFor(player, voiceServer, plugin) {
                PlayerSearchEvent(voicePlayer, query).callEvent()
            }) return@launch
        }

        val tracks = try {
            audioPlayerManager.getPlaylist("ytsearch:$query").await().tracks
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unexpected error"
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.search_fail", errorMsg)
            } else {
                player.sendMessage(Component.text("Failed to get search result: $errorMsg", NamedTextColor.RED))
            }
            debugLogger.log("Search failed", e)
            return@launch
        }

        if (tracks.isEmpty()) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.search_fail", "No results")
            } else {
                player.sendMessage(Component.text("No results found", NamedTextColor.RED))
            }
            return@launch
        }

        tracks.take(5).forEach {
            val command = "/disc burn ${it.identifier}"
            val cleanAuthor = it.info.author.replace(Regex("(?i)\\s*-\\s*topic$"), "").trim()
            if (voicePlayer != null) {
                val component = McTextComponent.translatable("pv.addon.discs.format.search_entry", it.info.title, cleanAuthor)
                    .hoverEvent(McTextHoverEvent.showText(McTextComponent.literal(command)))
                    .clickEvent(McTextClickEvent.suggestCommand(command))
                voicePlayer.instance.sendMessage(component)
            } else {
                val component = Component.text("${it.info.title} by $cleanAuthor", NamedTextColor.YELLOW)
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(command)))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(command))
                player.sendMessage(component)
            }
        }
    }}

    override fun checkCanExecute(sender: CommandSender): Boolean =
        sender.hasPermission("pv.addon.discs.search") || sender.hasPermission("vc.addon.discs.search")
}
