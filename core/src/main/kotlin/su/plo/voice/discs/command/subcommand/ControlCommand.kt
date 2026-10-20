package su.plo.voice.discs.command.subcommand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.key.Key
import org.koin.core.component.inject
import su.plo.voice.discs.item.DiscHelper
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
import su.plo.voice.discs.dialog.PaperPlaybackDialog
import su.plo.voice.discs.visualizer.LightBand
import su.plo.voice.discs.visualizer.VisualizerFacing
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
    private val discHelper: DiscHelper by inject()
    private val bassLamps: JukeboxBassLamps? by getterOrNull<JukeboxBassLamps>()
    private val visualizer: JukeboxVisualizer? by getterOrNull<JukeboxVisualizer>()
    private val screenManager: ScreenManager? by getterOrNull<ScreenManager>()
    private val progressDisplay: JukeboxProgressDisplay? by getterOrNull<JukeboxProgressDisplay>()
    private data class Session(val token: UUID, val block: Block, val controls: PlaybackControls?,
                               val record: ItemStack, val visualizerId: Int? = null, val expires: Long = System.currentTimeMillis() + 120_000)
    private data class NoteRequest(val block: Block, val left: Boolean? = null, val expires: Long = System.currentTimeMillis() + 120_000)
    private val noteRequests = ConcurrentHashMap<UUID, NoteRequest>()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val volumeSteps = listOf(1, 5, 10, 25)
    private val volumePresets = listOf(0, 10, 25, 50, 75, 100)
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
        if (block.asJukebox() == null) return
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
        // Leave either hand's discs to vanilla and the custom disc insertion listener.
        // Check the offhand before handling the main-hand event so it can still insert a disc.
        if (discHelper.isRecord(event.player.inventory.itemInMainHand) ||
            discHelper.isRecord(event.player.inventory.itemInOffHand)) return
        if (event.hand != EquipmentSlot.HAND) return
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

    fun show(player: Player, block: Block, crossfade: Boolean = false, hologram: Boolean = false, seek: Boolean = false, visualization: Boolean = false, selectedVisualizerId: Int? = null, speakers: Boolean = false, visualizerSelection: Boolean = false, queueMenu: Boolean = false, volumeMenu: Boolean = false, lampMenu: Boolean = false, screenMenu: Boolean = false) {
        val record = block.asJukebox()?.record?.clone() ?: return
        val controls = jukeboxes.controlsByBlock[block]
        val visualizers = visualizer?.instances(block).orEmpty()
        val selectedVisualizer = visualizers.firstOrNull { it.id == selectedVisualizerId } ?: visualizers.firstOrNull()
        val session = Session(UUID.randomUUID(), block, controls, record, visualizerId = selectedVisualizer?.id)
        sessions[player.uniqueId] = session
        val prefix = "/disc control ${session.token} "
        val buttons = mutableListOf<Pair<String, String>>()
        if (controls != null) {
            buttons += (if (controls.paused) "Resume" else "Pause") to (prefix + "pause")
            buttons += "Skip" to (prefix + "skip")
        }
        val queueLabels = mutableMapOf<String, Component>()
        val chest = block.getRelative(org.bukkit.block.BlockFace.UP).state as? org.bukkit.block.Chest
        if (controls == null && !playlists.isQueued(block) && chest != null)
            buttons += "Play chest" to (prefix + "playchest")
        val modes = playlists.playbackModes(block)
        buttons += "Queue" to (prefix + "queue_menu")
        buttons += "Volume" to (prefix + "volume_menu")
        buttons += "Audio settings" to (prefix + "audio_menu")
        val stereoLabel = when (jukeboxes.stereoMode(block)) {
            3 -> "Note block L / Note block R"
            else -> "Off"
        }
        buttons += "Speaker setup" to (prefix + "speakers")
        if (controls != null) {
            buttons += "Refresh" to (prefix + "refresh")
            buttons += "Stop playback" to (prefix + "stop")

            if (controls.seekable) {
                buttons += "Seek" to (prefix + "seek")
            }
        }
        if (controls == null) {
            if (record.hasIdentifier()) buttons += "Play disc" to (prefix + "play")
            buttons += "Refresh" to (prefix + "refresh")
            if (playlists.isQueued(block)) {
                buttons += "Stop playback" to (prefix + "stop")
            }
        }
        if (record.type != Material.AIR) buttons += "Eject disc" to (prefix + "eject")

        visualizer?.takeIf { it.supported }?.let {
            buttons += "Visualizer settings" to (prefix + "viz_menu")
        }

        bassLamps?.let {
            buttons += "Lights" to (prefix + "lamp_menu")
        }
        val canManageScreens = player.hasPermission("pv.addon.discs.screen") ||
            player.hasPermission("vc.addon.discs.screen") || player.isOp
        if (screenManager != null && canManageScreens) {
            buttons += "Screen" to (prefix + "screen_menu")
        }

        if (progressDisplay != null) {
            buttons += "Move hologram in world" to (prefix + "holo_edit")
        }

        val body = mutableListOf<Component>()
        if (controls != null) {
            body += Component.text(controls.title, NamedTextColor.GOLD)
            body += PlaybackDialogView.progress(controls.position, controls.duration, controls.isStream)
            body += Component.text("${if (controls.paused) "Paused" else "Playing"} · Volume ${controls.volume}%", NamedTextColor.GRAY)
        } else if (record.type != Material.AIR) {
            body += Component.text("Playback stopped. You can play or eject the disc.")
        } else {
            body += Component.text("Jukebox is idle.")
        }
        if (queueMenu) {
            buttons.clear()
            queueLabels.clear()
            body.clear()
            buttons += "Loop: ${if (modes.first) "On" else "Off"}" to (prefix + "loop")
            buttons += "Shuffle: ${if (modes.second) "On" else "Off"}" to (prefix + "shuffle")
            val queue = playlists.queueView(block)
            if (queue == null) body += Component.text("No tracks queued.", NamedTextColor.GRAY)
            else {
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
            buttons += "Refresh queue" to (prefix + "queue_menu")
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (volumeMenu) {
            buttons.clear()
            queueLabels.clear()
            body.clear()
            val volume = jukeboxes.volumeSetting(block)
            body += Component.text("Volume: $volume%", NamedTextColor.GOLD)
            body += Component.text(if (controls == null) "Applies when playback starts." else "Changes apply immediately.", NamedTextColor.GRAY)
            for (step in volumeSteps) {
                buttons += "Volume −$step%" to (prefix + "volume_minus_$step")
                buttons += "Volume +$step%" to (prefix + "volume_plus_$step")
            }
            for (preset in volumePresets) {
                val label = when (preset) { 0 -> "Mute"; 100 -> "Maximum · 100%"; else -> "Set $preset%" }
                buttons += label to (prefix + "volume_set_$preset")
            }
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (crossfade) {
            val queue = playlists.queueView(block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            if (queue != null) {
                body += Component.text("Crossfade: ${if (queue.crossfadeSeconds == 0) "Off" else "${queue.crossfadeSeconds}s"}", NamedTextColor.GOLD)
                body += Component.text("Changes apply to upcoming transitions. An active fade finishes at its original duration.", NamedTextColor.GRAY)
                buttons += "Crossfade -1s" to (prefix + "fadeless")
                buttons += "Crossfade +1s" to (prefix + "fademore")
                buttons += (if (queue.crossfadeSeconds > 0) "Disable crossfade" else "Enable crossfade") to (prefix + "fadetoggle")
            } else {
                body += Component.text("Start a queue to adjust crossfade settings.", NamedTextColor.GRAY)
            }
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (lampMenu) {
            val lamps = bassLamps ?: return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text("Lights: ${lamps.count(block)} linked", NamedTextColor.GOLD)
            body += Component.text("Choose a band, then click a lamp to link it or change its response.")
            bodyLampButtons(buttons, prefix, lamps.count(block))
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (screenMenu) {
            if (screenManager == null || !canManageScreens) return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            val screen = screenManager?.getScreenForJukebox(block)
            if (screen != null) {
                body += Component.text("Linked screen: ${screen.name} (${screen.columns}x${screen.rows} frames)", NamedTextColor.AQUA)
                body += Component.text("Subtitles: ${if (screen.subtitlesEnabled) "Enabled" else "Disabled"}", NamedTextColor.GRAY)
                buttons += "Subtitles: ${if (screen.subtitlesEnabled) "On" else "Off"}" to (prefix + "screen_subtitles")
                buttons += "Unlink Screen" to (prefix + "screen_unlink")
                buttons += "Clear Screen" to (prefix + "screen_clear")
            } else {
                body += Component.text("No screen linked.", NamedTextColor.GRAY)
                buttons += "Link Screen" to (prefix + "screen_link")
            }
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (hologram) {
            val pd = progressDisplay ?: return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            val offset = pd.getOffset(block)
            body += Component.text("Hologram Offset: X: ${offset.x}, Y: ${offset.y}, Z: ${offset.z}", NamedTextColor.GOLD)
            body += Component.text("Move in world equips a stick and grabs the hologram. Right-click to place/grab; scroll for distance; sneak-right-click to finish.", NamedTextColor.GRAY)
            buttons += "Move in world" to (prefix + "holo_edit")
            buttons += "Reset Position" to (prefix + "holo_reset")
            buttons += "Back to controls" to (prefix + "refresh")
        }
        if (speakers) {
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text("Stereo changes apply on the next song or after restarting playback.", NamedTextColor.GRAY)
            buttons += "Stereo: $stereoLabel" to (prefix + "stereo")
            buttons += "Link left note block" to (prefix + "note_left")
            buttons += "Link right note block" to (prefix + "note_right")
            if (jukeboxes.channelNoteLink(block, true) != null) buttons += "Unlink left note block" to (prefix + "note_left_unlink")
            if (jukeboxes.channelNoteLink(block, false) != null) buttons += "Unlink right note block" to (prefix + "note_right_unlink")
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (seek) {
            if (controls?.seekable != true) return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text(controls.title, NamedTextColor.GOLD)
            body += PlaybackDialogView.progress(controls.position, controls.duration, controls.isStream)
            body += Component.text("Seek audio and linked video together.", NamedTextColor.GRAY)
            val seekSteps = listOf(15 to "15s", 30 to "30s", 60 to "1m", 300 to "5m")
            for ((seconds, label) in seekSteps) {
                buttons += "−$label" to (prefix + "seek_minus_$seconds")
                buttons += "+$label" to (prefix + "seek_plus_$seconds")
            }
            buttons += "Refresh progress" to (prefix + "seek")
            buttons += "Back to playback" to (prefix + "refresh")
        }
        if (visualization || visualizerSelection) {
            if (visualizer == null) return show(player, block)
            buttons.clear()
            queueLabels.clear()
            body.clear()
            body += Component.text("Visualizers: ${visualizers.size}/8", NamedTextColor.GOLD)
            if (visualizerSelection && visualizers.size < 8) {
                buttons += "Add frequency bars" to (prefix + "viz_add_bars")
                buttons += "Add speaker driver" to (prefix + "viz_add_speaker")
            }
            if (!visualizerSelection) selectedVisualizer?.let { selected ->
                val layout = selected.layout
                body += Component.text("Editing #${selected.id}: ${selected.kind.label} · ${if (selected.enabled) "On" else "Off"} · Facing ${selected.facing}", NamedTextColor.GOLD)
                body += Component.text("Offset: ${layout.x}, ${layout.y}, ${layout.z} · Width: ${layout.width}× · Height: ${layout.height}×", NamedTextColor.GRAY)
                buttons += (if (selected.enabled) "Disable #${selected.id}" else "Enable #${selected.id}") to (prefix + "viz_toggle")
                buttons += ("Change to " + VisualizerKind.entries[(selected.kind.ordinal + 1) % VisualizerKind.entries.size].label) to (prefix + "viz_type")
                for (facing in VisualizerFacing.entries) {
                    val label = facing.name.lowercase().replaceFirstChar { it.uppercase() }
                    buttons += "${if (selected.facing == facing) "✓ " else ""}Face $label" to
                        (prefix + "viz_face_${facing.name.lowercase()}")
                }
                buttons += "Move / resize in world" to (prefix + "viz_edit")
                buttons += "Reset position, size and facing" to (prefix + "viz_reset")
                buttons += "Remove #${selected.id}" to (prefix + "viz_remove")
            }
            if (!visualizerSelection) buttons += "Select visualizer" to (prefix + "viz_menu")
            if (visualizerSelection) for (instance in visualizers) {
                buttons += "${if (instance.id == selectedVisualizer?.id) "▶ " else ""}Select #${instance.id} · ${instance.kind.label}" to (prefix + "viz_select_${instance.id}")
            }
            buttons += "Back to playback" to (prefix + "refresh")
        }
        val dialogTitle = if (lampMenu) "Lights" else if (screenMenu) "Screen" else if (queueMenu) "Queue" else if (volumeMenu) "Volume" else if (speakers) "Speaker setup" else if (visualizerSelection) "Select visualizer" else if (visualization) "Visualizer settings" else if (seek) "Seek" else if (hologram) "Move Hologram" else if (crossfade) "Audio settings" else "Jukebox controls"
        try {
            PaperPlaybackDialog.show(player, listOf(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), body)), buttons, queueLabels, dialogTitle, 2)
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
        for (band in LightBand.entries)
            buttons += "Set light: ${band.label}" to (prefix + "lamp_link_${band.name.lowercase()}")
        if (count > 0) buttons += "Unlink light ($count linked)" to (prefix + "lamp_unlink")
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
        if (arguments[2].startsWith("viz_face_")) {
            val facing = VisualizerFacing.entries.firstOrNull {
                arguments[2] == "viz_face_${it.name.lowercase()}"
            } ?: return
            val manager = visualizer ?: return
            val id = session.visualizerId ?: return
            manager.setFacing(session.block, id, facing)
            show(player, session.block, visualization = true, selectedVisualizerId = id)
            return
        }
        if (arguments[2].startsWith("viz_select_")) {
            val id = arguments[2].removePrefix("viz_select_").toIntOrNull() ?: return
            show(player, session.block, visualization = true, selectedVisualizerId = id)
            return
        }
        val seekSeconds = arguments[2].takeIf { it.startsWith("seek_minus_") || it.startsWith("seek_plus_") }
            ?.substringAfterLast('_')?.toIntOrNull()
        if (seekSeconds != null) {
            val allowedSteps = setOf(15, 30, 60, 300)
            if (controls == null || seekSeconds !in allowedSteps) return
            val delta = seekSeconds * 1000L * if (arguments[2].startsWith("seek_minus_")) -1 else 1
            controls.seekTo(controls.position + delta)
            show(player, session.block, seek = true)
            return
        }
        val volumeAction = arguments[2]
        if (volumeAction.startsWith("volume_minus_") || volumeAction.startsWith("volume_plus_") || volumeAction.startsWith("volume_set_")) {
            val amount = volumeAction.substringAfterLast('_').toIntOrNull() ?: return
            val current = jukeboxes.volumeSetting(session.block)
            val target = when {
                volumeAction.startsWith("volume_set_") && amount in volumePresets -> amount
                volumeAction.startsWith("volume_minus_") && amount in volumeSteps -> current - amount
                volumeAction.startsWith("volume_plus_") && amount in volumeSteps -> current + amount
                else -> return
            }.coerceIn(0, 100)
            if (controls != null) controls.changeVolume(target - current)
            else jukeboxes.restoreVolume(session.block, target)
            show(player, session.block, volumeMenu = true)
            return
        }
        when (arguments[2]) {
            "lamp_menu" -> { show(player, session.block, lampMenu = true); return }
            "screen_menu" -> { show(player, session.block, screenMenu = true); return }
            "queue_menu" -> { show(player, session.block, queueMenu = true); return }
            "volume_menu" -> { show(player, session.block, volumeMenu = true); return }
            "playchest" -> {
                val chest = session.block.getRelative(org.bukkit.block.BlockFace.UP).state as? org.bukkit.block.Chest ?: return
                playlists.startChestQueue(session.block, chest, player)
                return
            }
            "play" -> { if (controls == null) jukeboxes.playStoredDisc(session.block, player); return }
            "pause" -> controls?.togglePause()
            "skip" -> {
                if (controls != null) {
                    if (playlists.isQueued(session.block)) playlists.skipTrack(session.block)
                    else jukeboxes.stopPlayback(session.block)
                }
                return
            }
            "lamp_link", "lamp_link_bass", "lamp_link_mid", "lamp_link_highs", "lamp_unlink" -> {
                bassLamps?.beginLink(player, session.block, unlink = arguments[2] == "lamp_unlink",
                    band = LightBand.entries.firstOrNull { arguments[2] == "lamp_link_${it.name.lowercase()}" } ?: LightBand.BASS)
                return
            }
            "holo_edit" -> { worldEditor.editHologram(player, session.block); return }
            "viz_edit" -> { worldEditor.editVisualizer(player, session.block, session.visualizerId); return }
            "viz_menu" -> { show(player, session.block, visualizerSelection = true); return }
            "speakers" -> { show(player, session.block, speakers = true); return }
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
            "audio_menu", "crossfade" -> { show(player, session.block, crossfade = true); return }
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
                show(player, session.block, screenMenu = true)
                return
            }
            "screen_subtitles" -> {
                val sm = screenManager
                val screen = sm?.getScreenForJukebox(session.block)
                if (screen != null) {
                    val enabled = sm.toggleSubtitles(screen)
                    player.sendMessage(Component.text("Subtitles ${if (enabled) "enabled" else "disabled"} on screen '${screen.name}'.", NamedTextColor.YELLOW))
                }
                show(player, session.block, screenMenu = true)
                return
            }
            "screen_clear" -> {
                val sm = screenManager
                val screen = sm?.getScreenForJukebox(session.block)
                if (screen != null) {
                    sm.clearScreen(screen)
                    player.sendMessage(Component.text("Screen '${screen.name}' cleared.", NamedTextColor.YELLOW))
                }
                show(player, session.block, screenMenu = true)
                return
            }
            "refresh" -> {}
            else -> {
                val index = arguments[2].takeIf { it.startsWith("queue") }?.removePrefix("queue")?.toIntOrNull() ?: return
                playlists.jumpToTrack(session.block, index)
                return
            }
        }
        show(player, session.block, crossfade = arguments[2] in setOf("fadeless", "fademore", "fadetoggle"), speakers = arguments[2] == "stereo" || arguments[2].startsWith("note_"),
            queueMenu = arguments[2] in setOf("loop", "shuffle"), volumeMenu = arguments[2] in setOf("quieter", "louder"))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId); noteRequests.remove(event.player.uniqueId) }
}

