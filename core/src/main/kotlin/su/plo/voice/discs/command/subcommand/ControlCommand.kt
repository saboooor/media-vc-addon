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
import su.plo.voice.discs.visualizer.VisualizerKind
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.event.JukeboxBassLamps
import su.plo.voice.discs.event.JukeboxVisualizer
import su.plo.voice.discs.event.JukeboxProgressDisplay
import su.plo.voice.discs.screen.ScreenManager
import su.plo.voice.discs.utils.extend.hasIdentifier
import su.plo.voice.discs.utils.extend.asJukebox
import su.plo.voice.discs.utils.extend.getterOrNull
import su.plo.voice.discs.utils.extend.getValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class ControlCommand(
    private val jukeboxes: JukeboxEventListener,
    private val playlists: PlaylistCommand,
    private val worldEditor: VisualizerCommand,
) : SubCommand(), Listener {
    private val bassLamps: JukeboxBassLamps? by getterOrNull<JukeboxBassLamps>()
    private val visualizer: JukeboxVisualizer? by getterOrNull<JukeboxVisualizer>()
    private val screenManager: ScreenManager? by getterOrNull<ScreenManager>()
    private val progressDisplay: JukeboxProgressDisplay? by getterOrNull<JukeboxProgressDisplay>()
    private data class Session(val token: UUID, val block: Block, val controls: PlaybackControls?,
                               val record: ItemStack, val visualizerId: Int? = null, val expires: Long = System.currentTimeMillis() + 120_000)
    private data class NoteRequest(val block: Block, val left: Boolean? = null, val expires: Long = System.currentTimeMillis() + 120_000)
    private val noteRequests = ConcurrentHashMap<UUID, NoteRequest>()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    override val name = "control"
    override val permissions = listOf("control" to PermissionDefault.TRUE)

    override fun checkCanExecute(sender: CommandSender) =
        (sender.hasPermission("pv.addon.discs.control") || sender.hasPermission("vc.addon.discs.control")) &&
            (sender.hasPermission("pv.addon.discs.play") || sender.hasPermission("vc.addon.discs.play"))

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return
        val block = event.clickedBlock ?: return
        if (block.type == Material.NOTE_BLOCK && event.hand == EquipmentSlot.HAND) {
            val request = noteRequests[event.player.uniqueId] ?: return
            if (System.currentTimeMillis() > request.expires || !checkCanExecute(event.player)) {
                noteRequests.remove(event.player.uniqueId); return
            }
            if (event.player.gameMode == GameMode.SPECTATOR || request.block.type != Material.JUKEBOX ||
                block.world != request.block.world || block.location.distanceSquared(request.block.location) > 1024) {
                event.player.sendMessage(Component.text("Choose a note block within 32 blocks of the jukebox.")); return
            }
            event.isCancelled = true
            noteRequests.remove(event.player.uniqueId)
            val position = JukeboxBassLamps.Position.of(block)
            if (request.left != null) {
                if (jukeboxes.channelNoteLink(request.block, !request.left) == position) {
                    event.player.sendMessage(Component.text("Choose a different note block for each channel."))
                    noteRequests[event.player.uniqueId] = request
                    return
                }
                jukeboxes.linkChannelNote(request.block, request.left, position)
                jukeboxes.restoreStereo(request.block, 3)
            } else jukeboxes.linkNote(request.block, position)
            event.player.sendMessage(Component.text("Note block linked. Stereo changes apply on the next song or after restarting playback."))
            return
        }
        val jukebox = block.asJukebox() ?: return
        val emptyHands = event.player.inventory.itemInMainHand.type == Material.AIR &&
            event.player.inventory.itemInOffHand.type == Material.AIR
        if (event.player.isSneaking && !emptyHands) return
        val heldFrame = event.item?.type?.name in setOf("ITEM_FRAME", "GLOW_ITEM_FRAME")
        val offhandFrame = event.hand == EquipmentSlot.HAND && event.player.inventory.itemInMainHand.type == Material.AIR &&
            event.player.inventory.itemInOffHand.type.name in setOf("ITEM_FRAME", "GLOW_ITEM_FRAME")
        if (heldFrame || offhandFrame) {
            // Skip the jukebox's use action while leaving frame placement to vanilla/protection plugins.
            event.setUseInteractedBlock(Event.Result.DENY)
            return
        }
        if (event.hand != EquipmentSlot.HAND) return
        val hasCustomDisc = jukebox.record.hasIdentifier()
        val isLinked = screenManager?.getScreenForJukebox(block) != null
        val isEmptyHand = event.item == null || event.item?.type == Material.AIR
        if (!jukeboxes.isPlaying(block) && !playlists.isQueued(block) && !hasCustomDisc && !isLinked && !event.player.isSneaking && !isEmptyHand) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        if (!checkCanExecute(event.player) || event.player.gameMode == GameMode.SPECTATOR) {
            event.player.sendMessage(Component.text("No permission to control this jukebox."))
            return
        }
        show(event.player, block)
    }

    fun showHologramDialog(player: Player, block: Block) {
        show(player, block, hologram = true)
    }

    fun show(player: Player, block: Block, crossfade: Boolean = false, hologram: Boolean = false, seek: Boolean = false, visualization: Boolean = false, selectedVisualizerId: Int? = null) {
        val record = block.asJukebox()?.record?.clone() ?: return
        val controls = jukeboxes.controlsByBlock[block]
        val visualizers = visualizer?.instances(block).orEmpty()
        val selectedVisualizer = visualizers.firstOrNull { it.id == selectedVisualizerId } ?: visualizers.firstOrNull()
        val session = Session(UUID.randomUUID(), block, controls, record, visualizerId = selectedVisualizer?.id)
        sessions[player.uniqueId] = session
        val prefix = "/disc control ${session.token} "
        val buttons = mutableListOf<Pair<String, String>>()
        val queueLabels = mutableMapOf<String, Component>()
        val chest = block.getRelative(org.bukkit.block.BlockFace.UP).state as? org.bukkit.block.Chest
        if (controls == null && !playlists.isQueued(block) && chest != null)
            buttons += "Play chest" to (prefix + "playchest")
        val modes = playlists.playbackModes(block)
        buttons += "Loop: ${if (modes.first) "On" else "Off"}" to (prefix + "loop")
        buttons += "Shuffle: ${if (modes.second) "On" else "Off"}" to (prefix + "shuffle")
        val stereoLabel = when (jukeboxes.stereoMode(block)) {
            1 -> "Note block L / Jukebox R"
            2 -> "Jukebox L / Note block R"
            3 -> "Note block L / Note block R"
            else -> "Off"
        }
        buttons += "Stereo: $stereoLabel" to (prefix + "stereo")
        buttons += "Link left note block" to (prefix + "note_left")
        buttons += "Link right note block" to (prefix + "note_right")
        if (jukeboxes.channelNoteLink(block, true) != null) buttons += "Unlink left note block" to (prefix + "note_left_unlink")
        if (jukeboxes.channelNoteLink(block, false) != null) buttons += "Unlink right note block" to (prefix + "note_right_unlink")
        buttons += "Link note block" to (prefix + "note_link")
        if (jukeboxes.noteLink(block) != null) buttons += "Unlink note block" to (prefix + "note_unlink")
        if (controls != null) {
            buttons += (if (controls.paused) "Resume" else "Pause") to (prefix + "pause")
            buttons += "Refresh" to (prefix + "refresh")
            buttons += "Stop playback" to (prefix + "stop")
            if (playlists.isQueued(block)) buttons += "Crossfade settings" to (prefix + "crossfade")

            if (controls.seekable) {
                buttons += "Seek" to (prefix + "seek")
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

        visualizer?.takeIf { it.supported }?.let {
            buttons += "Visualizer settings" to (prefix + "viz_menu")
        }

        bassLamps?.let {
            bodyLampButtons(buttons, prefix, it.count(block))
        }
        val canManageScreens = player.hasPermission("pv.addon.discs.screen") ||
            player.hasPermission("vc.addon.discs.screen") || player.isOp
        if (screenManager != null && canManageScreens) {
            val linkedScreen = screenManager?.getScreenForJukebox(block)
            if (linkedScreen != null) {
                buttons += "Unlink Screen" to (prefix + "screen_unlink")
                buttons += "Clear Screen" to (prefix + "screen_clear")
            } else {
                buttons += "Link Screen" to (prefix + "screen_link")
            }
        }

        if (progressDisplay != null) {
            buttons += "Move hologram in world" to (prefix + "holo_edit")
        }

        val body = mutableListOf<Component>()
        screenManager?.getScreenForJukebox(block)?.let { s ->
            body += Component.text("Linked screen: ${s.name} (${s.columns}x${s.rows} frames)", NamedTextColor.AQUA)
        }
        if (controls != null) {
            body += Component.text("${if (controls.paused) "Paused" else "Playing"} · Volume ${controls.volume}%", NamedTextColor.GRAY)
        } else if (record.type != Material.AIR) {
            body += Component.text("Playback stopped. You can play or eject the disc.")
        } else {
            body += Component.text("Jukebox is idle.")
        }
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
        if (hologram) {
            val pd = progressDisplay ?: return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            val offset = pd.getOffset(block)
            body += Component.text("Hologram Offset: X: ${offset.x}, Y: ${offset.y}, Z: ${offset.z}", NamedTextColor.GOLD)
            body += Component.text("Hold a stick, then choose Move in world. Right-click to grab/place; scroll for distance; sneak-right-click to finish.", NamedTextColor.GRAY)
            buttons += "Move in world" to (prefix + "holo_edit")
            buttons += "Reset Position" to (prefix + "holo_reset")
            buttons += "Back to controls" to (prefix + "refresh")
        }
        if (seek) {
            if (controls?.seekable != true) return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text(controls.title, NamedTextColor.GOLD)
            body += Component.text("Seek audio and linked video together.", NamedTextColor.GRAY)
            for ((seconds, label) in listOf(15 to "15s", 30 to "30s", 60 to "1m", 300 to "5m")) {
                buttons += "−$label" to (prefix + "seek_minus_$seconds")
                buttons += "+$label" to (prefix + "seek_plus_$seconds")
            }
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (visualization) {
            val manager = visualizer ?: return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text("Visualizers: ${visualizers.size}/8", NamedTextColor.GOLD)
            if (visualizers.size < 8) {
                buttons += "Add frequency bars" to (prefix + "viz_add_bars")
                buttons += "Add speaker driver" to (prefix + "viz_add_speaker")
            }
            selectedVisualizer?.let { selected ->
                val layout = selected.layout
                body += Component.text("Editing #${selected.id}: ${selected.kind.label} · ${if (selected.enabled) "On" else "Off"} · Facing ${selected.facing}", NamedTextColor.GOLD)
                body += Component.text("Offset: ${layout.x}, ${layout.y}, ${layout.z} · Width: ${layout.width}× · Height: ${layout.height}×", NamedTextColor.GRAY)
                buttons += (if (selected.enabled) "Disable #${selected.id}" else "Enable #${selected.id}") to (prefix + "viz_toggle")
                buttons += ("Change to " + VisualizerKind.entries[(selected.kind.ordinal + 1) % VisualizerKind.entries.size].label) to (prefix + "viz_type")
                buttons += "Move / resize in world" to (prefix + "viz_edit")
                buttons += "Reset position, size and facing" to (prefix + "viz_reset")
                buttons += "Remove #${selected.id}" to (prefix + "viz_remove")
            }
            for (instance in visualizers) {
                buttons += "${if (instance.id == selectedVisualizer?.id) "▶ " else ""}Select #${instance.id} · ${instance.kind.label}" to (prefix + "viz_select_${instance.id}")
            }
            buttons += "Back to playback" to (prefix + "refresh")
        }
        val dialogTitle = if (visualization) "Visualizer settings" else if (seek) "Seek" else if (hologram) "Move Hologram" else if (crossfade) "Crossfade settings" else "Jukebox controls"
        try {
            PaperPlaybackDialog.show(player, body, buttons, queueLabels, dialogTitle, 2)
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

    private fun bodyLampButtons(buttons: MutableList<Pair<String, String>>, prefix: String, count: Int) {
        buttons += "Link bass lamp" to (prefix + "lamp_link")
        if (count > 0) buttons += "Unlink bass lamp ($count linked)" to (prefix + "lamp_unlink")
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
        if (arguments[2].startsWith("viz_select_")) {
            val id = arguments[2].removePrefix("viz_select_").toIntOrNull() ?: return
            show(player, session.block, visualization = true, selectedVisualizerId = id)
            return
        }
        when (arguments[2]) {
            "playchest" -> {
                val chest = session.block.getRelative(org.bukkit.block.BlockFace.UP).state as? org.bukkit.block.Chest ?: return
                playlists.startChestQueue(session.block, chest, player)
                return
            }
            "play" -> { if (controls == null) jukeboxes.playStoredDisc(session.block, player); return }
            "pause" -> controls?.togglePause()
            "lamp_link", "lamp_unlink" -> {
                bassLamps?.beginLink(player, session.block, unlink = arguments[2] == "lamp_unlink")
                return
            }
            "holo_edit" -> { worldEditor.editHologram(player, session.block); return }
            "viz_edit" -> { worldEditor.editVisualizer(player, session.block, session.visualizerId); return }
            "viz_menu" -> { show(player, session.block, visualization = true); return }
            "viz_add_bars", "viz_add_speaker" -> {
                val manager = visualizer ?: return
                val kind = if (arguments[2] == "viz_add_bars") VisualizerKind.BARS else VisualizerKind.SPEAKER
                val id = manager.add(session.block, kind)
                show(player, session.block, visualization = true, selectedVisualizerId = id ?: session.visualizerId)
                return
            }
            "viz_toggle", "viz_reset", "viz_remove", "viz_type" -> {
                val manager = visualizer ?: return
                val id = session.visualizerId ?: return
                when (arguments[2]) {
                    "viz_toggle" -> manager.toggle(session.block, id)
                    "viz_type" -> manager.changeKind(session.block, id)
                    "viz_remove" -> manager.delete(session.block, id)
                    "viz_reset" -> manager.resetLayout(session.block, id)
                }
                show(player, session.block, visualization = true, selectedVisualizerId = id)
                return
            }
            "seek" -> { show(player, session.block, seek = true); return }
            "seek_minus_15", "seek_minus_30", "seek_minus_60", "seek_minus_300",
            "seek_plus_15", "seek_plus_30", "seek_plus_60", "seek_plus_300" -> {
                val seconds = arguments[2].substringAfterLast('_').toLong()
                val delta = seconds * 1000 * if (arguments[2].startsWith("seek_minus_")) -1 else 1
                controls?.seekTo(controls.position + delta)
                show(player, session.block, seek = true)
                return
            }
            "loop" -> playlists.toggleLoop(session.block)
            "shuffle" -> playlists.toggleShuffle(session.block)
            "note_left", "note_right" -> {
                val left = arguments[2] == "note_left"
                noteRequests[player.uniqueId] = NoteRequest(session.block, left)
                player.sendMessage(Component.text("Right-click the ${if (left) "left" else "right"} channel note block within 32 blocks (2 minutes)."))
                return
            }
            "note_left_unlink", "note_right_unlink" -> {
                jukeboxes.linkChannelNote(session.block, arguments[2] == "note_left_unlink", null)
                player.sendMessage(Component.text("Channel unlinked. Link both channels before restarting two-note-block playback."))
            }
            "note_link" -> {
                noteRequests[player.uniqueId] = NoteRequest(session.block)
                player.sendMessage(Component.text("Right-click a note block within 32 blocks of this jukebox (2 minutes)."))
                return
            }
            "note_unlink" -> {
                jukeboxes.linkNote(session.block, null)
                player.sendMessage(Component.text("Note block unlinked. Adjacent detection resumes on the next song."))
            }
            "stereo" -> jukeboxes.cycleStereo(session.block)
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
            "holo_menu" -> { show(player, session.block, hologram = true); return }
            "holo_reset" -> {
                progressDisplay?.resetOffset(session.block)
                show(player, session.block, hologram = true)
                return
            }
            "screen_link" -> {
                val sm = screenManager ?: run {
                    player.sendMessage(Component.text("Screens are not enabled on this server.", NamedTextColor.RED))
                    return
                }
                var counter = 1
                while (sm.getScreen("screen-$counter") != null) {
                    counter++
                }
                val screenName = "screen-$counter"
                sm.startSelection(player, screenName, preLinkedJukebox = session.block)
                return
            }
            "screen_unlink" -> {
                val sm = screenManager
                val screen = sm?.getScreenForJukebox(session.block)
                if (screen != null) {
                    sm.unlinkJukebox(screen.name)
                    player.sendMessage(Component.text("Screen '${screen.name}' unlinked and deleted.", NamedTextColor.YELLOW))
                }
                show(player, session.block)
                return
            }
            "screen_clear" -> {
                val sm = screenManager
                val screen = sm?.getScreenForJukebox(session.block)
                if (screen != null) {
                    sm.clearScreen(screen)
                    player.sendMessage(Component.text("Screen '${screen.name}' cleared.", NamedTextColor.YELLOW))
                }
                show(player, session.block)
                return
            }
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
    fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId); noteRequests.remove(event.player.uniqueId) }
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
        "seek", "refresh" -> "item/clock_00"
        "back" -> "item/ender_pearl"
        "forward", "skip" -> "item/spectral_arrow"
        "quieter" -> "item/echo_shard"
        "louder" -> "item/goat_horn"
        "viz_menu", "viz_toggle", "viz_wider", "viz_narrower", "viz_taller", "viz_shorter", "viz_reset" -> "item/amethyst_shard"
        "crossfade", "fadeless", "fademore", "fadetoggle" -> "item/repeater"
        "stop" -> "item/redstone"
        "eject" -> "item/iron_pickaxe"
        "lamp_link", "lamp_unlink" -> "item/glowstone_dust"
        "screen_link" -> "item/item_frame"
        "screen_unlink" -> "item/shears"
        "screen_clear" -> "item/water_bucket"
        else -> when {
            command?.substringAfterLast(' ')?.startsWith("viz_") == true -> "item/amethyst_shard"
            command?.substringAfterLast(' ')?.startsWith("seek_minus_") == true -> "item/ender_pearl"
            command?.substringAfterLast(' ')?.startsWith("seek_plus_") == true -> "item/spectral_arrow"
            else -> "item/barrier"
        }
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

