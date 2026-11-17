package su.plo.voice.discs.command.subcommand

import ca.saboor.discs.dialog.PaperPlaybackDialog
import ca.saboor.discs.jellyfin.JellyfinClient
import ca.saboor.discs.jellyfin.JellyfinLibrary
import ca.saboor.discs.utils.extend.cleanArtistName
import ca.saboor.discs.utils.extend.cleanTrackName
import ca.saboor.discs.utils.extend.containsArtist
import ca.saboor.discs.utils.extend.suspendSyncFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.koin.core.component.inject
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.chat.style.McTextClickEvent
import su.plo.slib.api.chat.style.McTextHoverEvent
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.api.PlayerSearchEvent
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.utils.extend.*

class SearchCommand : SubCommand() {

    private val jellyfinLibrary: JellyfinLibrary by inject()

    override val name = "search"

    override val permissions = listOf(
        "search" to PermissionDefault.OP
    )

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {

        val player = source.asPlayer() ?: return listOf()
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }

        if (!player.hasPermission("pv.addon.discs.search") && !player.hasPermission("vc.addon.discs.search")) return listOf()
        if (arguments.size <= 1) return listOf()
        if (arguments.size == 2) {
            val prefix = arguments[1].lowercase()
            return SEARCH_SOURCES.filter { it.startsWith(prefix) }
        }

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

        val target = parseSearchArgs(arguments)
        if (target == null) {
            player.sendMessage(Component.text("Usage: /disc search <youtube|soundcloud|jellyfin> <query>", NamedTextColor.RED))
            return@launch
        }

        if (voicePlayer != null) {
            if (!suspendSyncFor(player, voiceServer, plugin) {
                PlayerSearchEvent(voicePlayer, target.query).callEvent()
            }) return@launch
        }

        val entries = try {
            when (target) {
                is SearchTarget.Youtube -> {
                    audioPlayerManager.getPlaylist(searchIdentifier(target)).await().tracks.map {
                        SearchEntry(it.identifier, it.info.title, it.info.author)
                    }
                }
                is SearchTarget.SoundCloud -> {
                    audioPlayerManager.getPlaylist(searchIdentifier(target)).await().tracks.map {
                        SearchEntry(it.info.uri?.takeIf { uri -> uri.isNotBlank() } ?: it.identifier, it.info.title, it.info.author)
                    }
                }
                is SearchTarget.Jellyfin -> {
                    val connection = jellyfinLibrary.connection(player.uniqueId)
                    if (connection == null) {
                        player.sendMessage(Component.text("You are not connected to Jellyfin. Run /disc jellyfin connect first.", NamedTextColor.RED))
                        return@launch
                    }
                    val page = withContext(Dispatchers.IO) {
                        JellyfinClient().browse(connection.session, 0, search = target.query, limit = 6)
                    }
                    page.items.filterNot { it.isSeries }.map { media ->
                        SearchEntry(jellyfinLibrary.grant(connection, media), media.title, media.artist)
                    }
                }
            }
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

        if (entries.isEmpty()) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.search_fail", "No results")
            } else {
                player.sendMessage(Component.text("No results found", NamedTextColor.RED))
            }
            return@launch
        }

        if (PaperPlaybackDialog.isAvailable()) {
            val topEntries = entries.take(6)
            val body = listOf(
                Component.text("Search: \"${target.query}\"", NamedTextColor.GOLD),
                Component.text("Hold a blank disc in hand and click a track to burn:", NamedTextColor.GRAY)
            )
            val buttons = mutableListOf<Pair<String, String>>()
            val labels = mutableMapOf<String, Component>()
            for (entry in topEntries) {
                val command = "/disc burn ${entry.identifier}"
                val cleanAuthor = entry.author.cleanArtistName()
                val cleanTitle = entry.title.cleanTrackName()
                val titleHasArtist = cleanAuthor.isNotBlank() && cleanTitle.containsArtist(cleanAuthor)
                val displayTitle = if (titleHasArtist) cleanTitle else "$cleanTitle - $cleanAuthor"
                val shortTitle = displayTitle.take(36)
                buttons += shortTitle to command
                labels[command] = PaperPlaybackDialog.icon("item/music_disc_cat", Component.text(shortTitle, NamedTextColor.YELLOW))
            }

            try {
                PaperPlaybackDialog.show(player, body, buttons, labels, "Search: ${target.query.take(20)}", columns = 1, buttonWidth = 320)
                return@launch
            } catch (_: Throwable) {
                // Fall back to chat format below
            }
        }

        entries.take(5).forEach {
            val command = "/disc burn ${it.identifier}"
            val cleanAuthor = it.author.cleanArtistName()
            val cleanTitle = it.title.cleanTrackName()
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

    internal data class SearchEntry(
        val identifier: String,
        val title: String,
        val author: String,
    )

    internal sealed class SearchTarget {
        abstract val query: String

        data class Youtube(override val query: String) : SearchTarget()
        data class SoundCloud(override val query: String) : SearchTarget()
        data class Jellyfin(override val query: String) : SearchTarget()
    }

    companion object {
        internal val SEARCH_SOURCES = listOf("youtube", "soundcloud", "jellyfin")

        /**
         * Parses `/disc search <youtube|soundcloud|jellyfin> <query>` arguments into a typed target,
         * or returns `null` when the source or query is missing or unrecognized.
         */
        internal fun parseSearchArgs(arguments: Array<out String>): SearchTarget? {
            if (arguments.size < 3) return null
            val query = arguments.drop(2).joinToString(" ").trim()
            if (query.isBlank()) return null
            return when (arguments[1].trim().lowercase()) {
                "youtube", "yt" -> SearchTarget.Youtube(query)
                "soundcloud", "sc" -> SearchTarget.SoundCloud(query)
                "jellyfin", "jf" -> SearchTarget.Jellyfin(query)
                else -> null
            }
        }

        internal fun searchIdentifier(target: SearchTarget): String = when (target) {
            is SearchTarget.Youtube -> "ytsearch:${target.query}"
            is SearchTarget.SoundCloud -> "scsearch:${target.query}"
            is SearchTarget.Jellyfin -> error("Jellyfin search uses JellyfinClient.browse directly")
        }

        internal fun searchIdentifier(query: String): String {
            val trimmed = query.trim()
            val lower = trimmed.lowercase()
            return when {
                lower.startsWith("ytsearch:") || lower.startsWith("ytmsearch:") || lower.startsWith("scsearch:") -> trimmed
                lower.startsWith("soundcloud:") -> "scsearch:${trimmed.substringAfter(':').trimStart()}"
                lower.startsWith("sc:") -> "scsearch:${trimmed.substringAfter(':').trimStart()}"
                else -> "ytsearch:$trimmed"
            }
        }
    }
}
