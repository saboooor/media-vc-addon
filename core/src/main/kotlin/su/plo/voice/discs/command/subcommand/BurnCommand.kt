package su.plo.voice.discs.command.subcommand

import ca.saboor.discs.utils.extend.burnMetadata
import ca.saboor.discs.utils.extend.formatTrackDisplay
import ca.saboor.discs.utils.extend.suspendSyncAt
import ca.saboor.discs.utils.extend.suspendSyncFor
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import ca.saboor.discs.playlist.PublicPlaylistClient
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.inject
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.GoatHornManager
import su.plo.voice.discs.api.PlayerBurnEvent
import su.plo.voice.discs.api.PrePlayerBurnEvent
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.item.DiscHelper
import su.plo.voice.discs.item.GoatHornHelper
import su.plo.voice.discs.utils.extend.*

class BurnCommand : SubCommand() {

    private val discHelper: DiscHelper by inject()
    private val hornHelper: GoatHornHelper by inject()
    private val hornManager: GoatHornManager by inject()

    override val name = "burn"

    override val permissions = listOf(
        "burn" to PermissionDefault.OP,
        "burn.burnable_check_bypass" to PermissionDefault.OP,
    )

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {
        val player = source.asPlayer() ?: return listOf()
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }

        if (!player.hasPermission("pv.addon.discs.burn") && !player.hasPermission("vc.addon.discs.burn")) return listOf()

        if (arguments.size < 2) return listOf()
        if (arguments.size == 2) {
            return if (voicePlayer != null && voiceServer != null) {
                listOf(
                    McTextComponent.translatable("pv.addon.discs.arg.url")
                        .render(voicePlayer, voiceServer!!)
                        .toPlainText()
                )
            } else {
                listOf("<url>")
            }
        }
        return if (voicePlayer != null && voiceServer != null) {
            listOf(
                McTextComponent.translatable("pv.addon.discs.arg.label")
                    .render(voicePlayer, voiceServer!!)
                    .toPlainText()
            )
        } else {
            listOf("[label]")
        }
    }

    private fun checkBurnable(player: Player, voicePlayer: VoicePlayer?, item: ItemStack): Boolean {

        if (!discHelper.isRecord(item) && !(config.goatHorn.enabled && item.type.name == "GOAT_HORN")) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.not_a_record")
            } else {
                player.sendMessage(Component.text("You need to have a record in your hand to burn it", NamedTextColor.RED))
            }
            return false
        }

        if (
            config.burnableTag.requireBurnableTag &&
            (
                !item.itemMeta.persistentDataContainer.has(AddonKeys.burnableKey, PersistentDataType.BYTE) &&
                !player.hasPermission("pv.addon.discs.burn.burnable_check_bypass") &&
                !player.hasPermission("vc.addon.discs.burn.burnable_check_bypass")
            )
        ) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.not_burnable")
            } else {
                player.sendMessage(Component.text("This record is not burnable", NamedTextColor.RED))
            }
            return false
        }

        return true
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) { scope.launch {

        val player = sender.asPlayer() ?: run {
            sender.sendMessage("Player only command")
            return@launch
        }
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }

        if (!player.hasPermission("pv.addon.discs.burn") && !player.hasPermission("vc.addon.discs.burn")) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.no_permission")
            } else {
                player.sendMessage(Component.text("No permission", NamedTextColor.RED))
            }
            return@launch
        }

        val identifier = arguments.getOrNull(1) ?: run {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.usage.burn")
            } else {
                player.sendMessage(Component.text("Usage: /disc burn <url> [label]", NamedTextColor.RED))
            }
            return@launch
        }

        if (voicePlayer != null) {
            if (!suspendSyncFor(player, voiceServer, plugin) {
                PrePlayerBurnEvent(voicePlayer, identifier).callEvent()
            }) return@launch
        }

        var burnIdentifier = identifier
        val track = try {
            val song = withContext(Dispatchers.IO) { PublicPlaylistClient().loadSong(identifier) }
            if (song == null) audioPlayerManager.getTrack(identifier).await()
            else withTimeout(30_000L) {
                audioPlayerManager.getPlaylist("ytsearch:${song.query}").await().tracks.firstOrNull()
                    ?: error("No playable match for ${song.artist} - ${song.title}")
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unexpected error"
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.error.get_track_fail", errorMsg)
            } else {
                player.sendMessage(Component.text("Failed to get track: $errorMsg", NamedTextColor.RED))
            }
            debugLogger.log("Failed to load track", e)
            return@launch
        }

        if (identifier.startsWith("spotify:") || runCatching { java.net.URI(identifier).host }.getOrNull() in setOf("open.spotify.com", "music.apple.com"))
            burnIdentifier = track.info.uri?.takeIf { it.isNotBlank() } ?: track.identifier

        val name = arguments.drop(2)
            .joinToString(" ")
            .ifEmpty { formatTrackDisplay(track.info.title, track.info.author) }

        val item = suspendSyncFor(player, voiceServer, plugin) { player.inventory.itemInMainHand }

        if (!checkBurnable(player, voicePlayer, item)) return@launch

        val isGoatHorn = item.type.name == "GOAT_HORN"

        if (isGoatHorn && !hornManager.canPlay(track)) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable(
                    "pv.addon.discs.error.horn_too_long", config.goatHorn.maxDurationSeconds
                )
            } else {
                player.sendMessage(Component.text("Track is too long. Maximum duration in seconds: ${config.goatHorn.maxDurationSeconds}", NamedTextColor.RED))
            }
            return@launch
        }

        if (voicePlayer != null) {
            if (!suspendSyncFor(player, voiceServer, plugin) {
                PlayerBurnEvent(voicePlayer, track, item).callEvent()
            }) return@launch
        }

        suspendSyncAt(player.location, voiceServer, plugin) {
            if (Bukkit.getServer().getMinecraftVersionInt() >= 12103) {
                discHelper.showSongTooltip(item, false)
            }

            val hasIdentifier = item.hasIdentifier()

            if (isGoatHorn && !hasIdentifier) {
                val instrument = hornHelper.getInstrument(item)
                if (instrument.isNotEmpty()) item.editMeta { meta ->
                    meta.persistentDataContainer.set(AddonKeys.instrumentKey, PersistentDataType.STRING, instrument)
                }
            }
            item.burnMetadata(burnIdentifier, name, config, discHelper)

            if (isGoatHorn) {
                hornHelper.setEmptyInstrument(item)
            }

            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable("pv.addon.discs.success.burn", name)
            } else {
                player.sendMessage(Component.text("Disc burned. Label: $name", NamedTextColor.GREEN))
            }
        }
    }}

    override fun checkCanExecute(sender: CommandSender): Boolean =
        sender.hasPermission("pv.addon.discs.burn") || sender.hasPermission("vc.addon.discs.burn")
}
