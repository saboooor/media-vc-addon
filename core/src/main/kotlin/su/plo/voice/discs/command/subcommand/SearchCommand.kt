package su.plo.voice.discs.command.subcommand

import ca.saboor.discs.dialog.PaperPlaybackDialog
import ca.saboor.discs.utils.extend.cleanArtistName
import ca.saboor.discs.utils.extend.cleanTrackName
import ca.saboor.discs.utils.extend.containsArtist
import ca.saboor.discs.utils.extend.suspendSyncFor
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

        val query = arguments.drop(1).joinToString(" ").trim()
        if (query.isBlank()) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.arg.search")
            } else {
                player.sendMessage(Component.text("Usage: /disc search <query>", NamedTextColor.RED))
            }
            return@launch
        }

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

        if (PaperPlaybackDialog.isAvailable()) {
            val topTracks = tracks.take(6)
            val body = listOf(
                Component.text("Search: \"$query\"", NamedTextColor.GOLD),
                Component.text("Hold a blank disc in hand and click a track to burn:", NamedTextColor.GRAY)
            )
            val buttons = mutableListOf<Pair<String, String>>()
            val labels = mutableMapOf<String, Component>()
            for (track in topTracks) {
                val command = "/disc burn ${track.identifier}"
                val cleanAuthor = track.info.author.cleanArtistName()
                val cleanTitle = track.info.title.cleanTrackName()
                val titleHasArtist = cleanAuthor.isNotBlank() && cleanTitle.containsArtist(cleanAuthor)
                val displayTitle = if (titleHasArtist) cleanTitle else "$cleanTitle - $cleanAuthor"
                val shortTitle = displayTitle.take(36)
                buttons += shortTitle to command
                labels[command] = PaperPlaybackDialog.icon("item/music_disc_cat", Component.text(shortTitle, NamedTextColor.YELLOW))
            }

            try {
                PaperPlaybackDialog.show(player, body, buttons, labels, "Search: ${query.take(20)}", columns = 1, buttonWidth = 320)
                return@launch
            } catch (_: Throwable) {
                // Fall back to chat format below
            }
        }

        tracks.take(5).forEach {
            val command = "/disc burn ${it.identifier}"
            val cleanAuthor = it.info.author.cleanArtistName()
            val cleanTitle = it.info.title.cleanTrackName()
            val titleHasArtist = cleanAuthor.isNotBlank() && cleanTitle.containsArtist(cleanAuthor)
            if (voicePlayer != null) {
                val component = if (titleHasArtist) {
                    McTextComponent.literal(cleanTitle)
                } else {
                    McTextComponent.translatable("pv.addon.discs.format.search_entry", cleanTitle, cleanAuthor)
                }
                    .hoverEvent(McTextHoverEvent.showText(McTextComponent.literal(command)))
                    .clickEvent(McTextClickEvent.suggestCommand(command))
                voicePlayer.instance.sendMessage(component)
            } else {
                val text = if (titleHasArtist) cleanTitle else "$cleanTitle by $cleanAuthor"
                val component = Component.text(text, NamedTextColor.YELLOW)
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(command)))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(command))
                player.sendMessage(component)
            }
        }
    }}

    override fun checkCanExecute(sender: CommandSender): Boolean =
        sender.hasPermission("pv.addon.discs.search") || sender.hasPermission("vc.addon.discs.search")
}
