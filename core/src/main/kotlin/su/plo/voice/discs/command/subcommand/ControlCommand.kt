package su.plo.voice.discs.command.subcommand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.PlaybackDialogView
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.utils.extend.hasIdentifier
import su.plo.voice.discs.utils.extend.asJukebox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class ControlCommand(
    private val jukeboxes: JukeboxEventListener,
    private val playlists: PlaylistCommand,
) : SubCommand(), Listener {
    private data class Session(val token: UUID, val block: Block, val controls: PlaybackControls?,
                               val record: ItemStack, val expires: Long = System.currentTimeMillis() + 120_000)
    private val sessions = ConcurrentHashMap<UUID, Session>()
    override val name = "control"
    override val permissions = listOf("control" to PermissionDefault.TRUE)

    override fun checkCanExecute(sender: CommandSender) =
        (sender.hasPermission("pv.addon.discs.control") || sender.hasPermission("vc.addon.discs.control")) &&
            (sender.hasPermission("pv.addon.discs.play") || sender.hasPermission("vc.addon.discs.play"))

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND ||
            event.useInteractedBlock() == Event.Result.DENY) return
        val block = event.clickedBlock ?: return
        val jukebox = block.asJukebox() ?: return
        if (!jukeboxes.isPlaying(block) && !playlists.isQueued(block) && !jukebox.record.hasIdentifier()) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        if (!checkCanExecute(event.player) || event.player.gameMode == GameMode.SPECTATOR) {
            event.player.sendMessage(Component.text("No permission to control this jukebox."))
            return
        }
        show(event.player, block)
    }

    private fun show(player: Player, block: Block, crossfade: Boolean = false) {
        val record = block.asJukebox()?.record?.clone() ?: return
        val controls = jukeboxes.controlsByBlock[block]
        val session = Session(UUID.randomUUID(), block, controls, record)
        sessions[player.uniqueId] = session
        val prefix = "/disc control ${session.token} "
        val buttons = mutableListOf<Pair<String, String>>()
        val queueLabels = mutableMapOf<String, Component>()
        if (controls != null) {
            buttons += (if (controls.paused) "Resume" else "Pause") to (prefix + "pause")
            buttons += "Refresh" to (prefix + "refresh")
            buttons += "Stop playback" to (prefix + "stop")
            if (playlists.isQueued(block)) buttons += "Crossfade settings" to (prefix + "crossfade")

            if (controls.seekable) {
                buttons += "Back 15 seconds" to (prefix + "back")
                buttons += "Forward 15 seconds" to (prefix + "forward")
            }
            buttons += "Volume -10%" to (prefix + "quieter")
            buttons += "Volume +10%" to (prefix + "louder")
        }
        if (controls == null) {
            if (record.hasIdentifier()) buttons += "Play disc" to (prefix + "play")
            buttons += "Refresh" to (prefix + "refresh")
            if (playlists.isQueued(block)) {
                buttons += "Stop playback" to (prefix + "stop")
                buttons += "Crossfade settings" to (prefix + "crossfade")
            }
        }
        if (record.type != Material.AIR) buttons += "Eject disc" to (prefix + "eject")
        val body = mutableListOf<Component>()
        if (controls != null) {
            body += Component.text("${if (controls.paused) "Paused" else "Playing"} · Volume ${controls.volume}%", NamedTextColor.GRAY)
        } else body += Component.text("Playback stopped. You can play or eject the disc.")
        playlists.queueView(block)?.let { queue ->
            body += PaperPlaybackDialog.icon("item/book", Component.text("Queue · ${queue.currentIndex + 1} of ${queue.items.size}", NamedTextColor.GOLD))
            queue.items.forEachIndexed { index, item ->
                val marker = when {
                    index < queue.currentIndex -> "✓"
                    index == queue.currentIndex -> "▶"
                    else -> "·"
                }
                val color = when {
                    index < queue.currentIndex -> NamedTextColor.DARK_GRAY
                    index == queue.currentIndex -> NamedTextColor.GREEN
                    else -> NamedTextColor.GRAY
                }
                val command = prefix + "queue$index"
                buttons += "$marker ${index + 1}. ${item.title}" to command
                queueLabels[command] = PaperPlaybackDialog.icon(item.sprite, Component.text("$marker ${index + 1}. ${item.title.take(36)}", color))
            }
        }
        if (crossfade) {
            val queue = playlists.queueView(block) ?: return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text("Crossfade: ${if (queue.crossfadeSeconds == 0) "Off" else "${queue.crossfadeSeconds}s"}", NamedTextColor.GOLD)
            body += Component.text("Changes apply to upcoming transitions. An active fade finishes at its original duration.", NamedTextColor.GRAY)
            buttons += "Crossfade -1s" to (prefix + "fadeless")
            buttons += "Crossfade +1s" to (prefix + "fademore")
            buttons += (if (queue.crossfadeSeconds > 0) "Disable crossfade" else "Enable crossfade") to (prefix + "fadetoggle")
            buttons += "Back to playback" to (prefix + "refresh")
        }
        try {
            PaperPlaybackDialog.show(player, body, buttons, queueLabels, if (crossfade) "Crossfade settings" else "Jukebox controls", 2)
        } catch (_: ClassNotFoundException) {
            // Preserve older supported Minecraft releases which predate native dialogs.
            body.forEach { player.sendMessage(it) }
            buttons.forEach { (label, command) ->
                player.sendMessage(Component.text("[$label]").clickEvent(ClickEvent.runCommand(command)))
            }
        } catch (e: Exception) {
            plugin.slF4JLogger.error("Could not open jukebox dialog", e)
            player.sendMessage(Component.text("Could not open playback controls. Check the server console."))
        }
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (arguments.size != 3 || arguments[1] != session.token.toString()) return
        if (!checkCanExecute(player) || player.gameMode == GameMode.SPECTATOR ||
            System.currentTimeMillis() > session.expires || player.world != session.block.world ||
            player.location.distanceSquared(session.block.location.add(0.5, 0.5, 0.5)) > 64 ||
            !session.block.world.isChunkLoaded(session.block.x shr 4, session.block.z shr 4) ||
            session.block.asJukebox()?.record != session.record ||
            jukeboxes.controlsByBlock[session.block] !== session.controls) {
            sessions.remove(player.uniqueId, session)
            player.sendMessage(Component.text("These controls have expired. Right-click the jukebox again."))
            return
        }
        sessions.remove(player.uniqueId, session)
        val controls = session.controls
        when (arguments[2]) {
            "play" -> { if (controls == null) jukeboxes.playStoredDisc(session.block, player); return }
            "pause" -> controls?.togglePause()
            "back" -> controls?.seekTo(controls.position - 15_000)
            "forward" -> controls?.seekTo(controls.position + 15_000)
            "quieter" -> controls?.changeVolume(-10)
            "louder" -> controls?.changeVolume(10)
            "stop", "eject" -> {
                playlists.stopQueue(session.block)
                jukeboxes.stopPlayback(session.block)
                if (arguments[2] == "eject") session.block.asJukebox()?.eject()
                return
            }
            "crossfade" -> { show(player, session.block, crossfade = true); return }
            "fadeless" -> playlists.changeCrossfade(session.block, -1)
            "fademore" -> playlists.changeCrossfade(session.block, 1)
            "fadetoggle" -> playlists.toggleCrossfade(session.block)
            "refresh" -> {}
            else -> {
                val index = arguments[2].takeIf { it.startsWith("queue") }?.removePrefix("queue")?.toIntOrNull() ?: return
                playlists.jumpToTrack(session.block, index)
                return
            }
        }
        show(player, session.block, crossfade = arguments[2] in setOf("fadeless", "fademore", "fadetoggle"))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId) }
}

/** Paper's dialog factory, as used by Lumistones, loaded only on dialog-capable servers. */
private object PaperPlaybackDialog {
    private const val ROOT = "io.papermc.paper.registry.data.dialog."

    private fun invoke(type: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val method = type.methods.first { method ->
            method.name == name && method.parameterCount == args.size &&
                method.parameterTypes.zip(args).all { (parameter, argument) ->
                    argument == null || parameter.isInstance(argument) ||
                        parameter == Int::class.javaPrimitiveType && argument is Int ||
                        parameter == Boolean::class.javaPrimitiveType && argument is Boolean
                }
        }
        method.trySetAccessible()
        return method.invoke(target, *args)
    }
    private fun call(target: Any, name: String, vararg args: Any?) = invoke(target.javaClass, target, name, *args)!!
    private fun static(type: String, name: String, vararg args: Any?) = invoke(Class.forName(type), null, name, *args)!!

    fun icon(sprite: String, label: Component): Component {
        return try {
            val contents = static("net.kyori.adventure.text.object.ObjectContents", "sprite",
                Key.key(PlaybackDialogView.spriteAtlas(Bukkit.getMinecraftVersion())), Key.key("minecraft:$sprite"))
            (static("net.kyori.adventure.text.Component", "object", contents) as Component)
                .append(Component.text(" ")).append(label)
        } catch (_: ClassNotFoundException) {
            label
        } catch (_: NoSuchElementException) {
            label
        }
    }

    private fun buttonSprite(command: String?): String = when (command?.substringAfterLast(' ')) {
        "play", "pause" -> "item/music_disc_cat"
        "refresh" -> "item/clock_00"
        "back" -> "item/ender_pearl"
        "forward", "skip" -> "item/spectral_arrow"
        "quieter" -> "item/echo_shard"
        "louder" -> "item/goat_horn"
        "crossfade", "fadeless", "fademore", "fadetoggle" -> "item/repeater"
        "stop" -> "item/redstone"
        "eject" -> "item/iron_pickaxe"
        else -> "item/barrier"
    }

    fun show(player: Player, body: List<Component>, buttons: List<Pair<String, String>>, queueLabels: Map<String, Component>, title: String, columns: Int) {
        val dialogClass = Class.forName("io.papermc.paper.dialog.Dialog")
        fun button(label: String, command: String?) = static(ROOT + "ActionButton", "create",
            queueLabels[command] ?: icon(buttonSprite(command), Component.text(label)), Component.text(label), 150,
            command?.let { static(ROOT + "action.DialogAction", "staticAction", ClickEvent.runCommand(it)) })
        val baseBuilder = static(ROOT + "DialogBase", "builder", Component.text(title))
        call(baseBuilder, "pause", false)
        call(baseBuilder, "canCloseWithEscape", true)
        call(baseBuilder, "body", body.map { static(ROOT + "body.DialogBody", "plainMessage", it, 350) })
        val base = call(baseBuilder, "build")
        val type = static(ROOT + "type.DialogType", "multiAction",
            buttons.map { (label, command) -> button(label, command) }, button("Close", null), columns)
        val dialog = invoke(dialogClass, null, "create", Consumer<Any> { factory ->
            val builder = call(factory, "empty")
            call(builder, "base", base)
            call(builder, "type", type)
        })!!
        invoke(player.javaClass, player, "showDialog", dialog)
    }
}
