package su.plo.voice.discs.command.subcommand

import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxVisualizer
import su.plo.voice.discs.event.JukeboxProgressDisplay
import su.plo.voice.discs.utils.extend.getterOrNull
import su.plo.voice.discs.utils.extend.getValue
import su.plo.voice.discs.utils.extend.suspendSyncAt
import su.plo.voice.discs.visualizer.VisualizerFacing
import su.plo.voice.discs.visualizer.VisualizerInstance
import su.plo.voice.discs.visualizer.VisualizerKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/** An ordinary stick acts as an editing wand without replacing inventory contents. */
class VisualizerCommand(private val visualizers: JukeboxVisualizer) : SubCommand(), Listener {
    override val name = "visualizer"
    override val permissions = listOf("control" to PermissionDefault.TRUE)
    private val holograms: JukeboxProgressDisplay? by getterOrNull<JukeboxProgressDisplay>()
    private class Editor(val block: Block, var id: Int, val hologram: Boolean = false) {
        var grabbed = false
        var distance = 4.0
        var grabOffset = Vector()
        var expires = System.currentTimeMillis() + 300_000L
    }
    private val editors = ConcurrentHashMap<UUID, Editor>()
    private var updater: Job? = null
    override fun checkCanExecute(sender: CommandSender) =
        (sender.hasPermission("pv.addon.discs.control") || sender.hasPermission("vc.addon.discs.control")) &&
            (sender.hasPermission("pv.addon.discs.play") || sender.hasPermission("vc.addon.discs.play"))

    private fun message(player: Player, text: String) = player.sendMessage(Component.text(text, NamedTextColor.YELLOW))
    private fun anchor(block: Block, spec: VisualizerInstance) = block.location.add(0.5 + spec.layout.x,
        (if (block.getRelative(0, 1, 0).state is Chest) 2.1 else 1.1) + spec.layout.y, 0.5 + spec.layout.z).toVector()
    private fun select(editor: Editor, id: Int) {
        editor.id = id
        editor.grabbed = false
        visualizers.setEditing(editor.block, id)
    }
    private fun finish(player: Player, text: String = "Visualizer editing finished. Changes saved.") {
        val editor = editors.remove(player.uniqueId) ?: return
        if (editor.hologram) holograms?.let { it.saveOffsets(); it.setEditing(editor.block, false) } else visualizers.setEditing(editor.block, null)
        message(player, if (editor.hologram) "Hologram editing finished. Changes saved." else text)
    }

    fun editHologram(player: Player, block: Block) {
        if (!checkCanExecute(player) || player.gameMode == GameMode.SPECTATOR || holograms?.supported != true) return
        if (player.inventory.itemInMainHand.type != Material.STICK) {
            message(player, "Hold a stick, then run /disc hologram edit."); return
        }
        if (editors.any { it.key != player.uniqueId && it.value.block == block }) {
            message(player, "Someone is already editing this jukebox."); return
        }
        finish(player)
        editors[player.uniqueId] = Editor(block, 0, hologram = true)
        holograms?.setEditing(block, true)
        message(player, "Right-click: grab/place hologram. Look around to move. Scroll: distance. Sneak while moving: snap to grid. Sneak-right-click: finish.")
    }

    fun editVisualizer(player: Player, block: Block, selectedId: Int? = null) {
        if (!checkCanExecute(player) || player.gameMode == GameMode.SPECTATOR || !visualizers.supported) return
        if (player.inventory.itemInMainHand.type != Material.STICK) {
            message(player, "Hold a stick and choose Move in world again, or run /disc visualizer edit."); return
        }
        if (editors.any { it.key != player.uniqueId && it.value.block == block }) {
            message(player, "Someone is already editing this jukebox's visualizers."); return
        }
        finish(player)
        val id = visualizers.instances(block).firstOrNull { it.id == selectedId }?.id
            ?: visualizers.instances(block).firstOrNull()?.id ?: visualizers.add(block, VisualizerKind.BARS) ?: return
        val editor = Editor(block, id)
        editors[player.uniqueId] = editor
        select(editor, id)
        message(player, "Right-click: grab/release. Look around to move. Scroll: size (or distance while grabbed). Sneak-scroll: width. Swap hands: rotate.")
        message(player, "Left-click: select aimed visualizer (sneak-click cycles). Sneak-right-click: finish. /disc visualizer add bars|speaker, remove, type, toggle, reset, next, done.")
    }

    fun finishEditing(player: Player) = finish(player)

    private fun grab(player: Player, editor: Editor, center: Vector) {
        editor.grabbed = !editor.grabbed
        if (editor.grabbed) {
            editor.distance = center.distance(player.eyeLocation.toVector()).coerceIn(1.0, 24.0)
            editor.grabOffset = center.subtract(player.eyeLocation.toVector().add(player.eyeLocation.direction.multiply(editor.distance)))
        } else if (editor.hologram) holograms?.saveOffsets()
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: return
        if (!checkCanExecute(player) || player.gameMode == GameMode.SPECTATOR || !visualizers.supported) {
            message(player, "Visualizer editing is unavailable or you lack playback/control permission.")
            return
        }
        val action = arguments.getOrNull(1)?.lowercase() ?: "edit"
        if (action == "done") { finish(player); return }
        if (action == "edit") {
            if (player.inventory.itemInMainHand.type != Material.STICK) {
                message(player, "Hold a stick to use as your visualizer editing wand."); return
            }
            val target = player.getTargetBlockExact(6)
            val block = when {
                target?.type == Material.JUKEBOX -> target
                target?.state is Chest && target.getRelative(0, -1, 0).type == Material.JUKEBOX -> target.getRelative(0, -1, 0)
                else -> null
            } ?: run { message(player, "Look at a jukebox or its chest within 6 blocks."); return }
            editVisualizer(player, block)
            return
        }
        val editor = editors[player.uniqueId] ?: run { message(player, "Start with /disc visualizer edit while looking at a jukebox."); return }
        if (!valid(player, editor)) { finish(player); return }
        if (editor.hologram) { message(player, "Finish hologram editing first with sneak-right-click or /disc hologram done."); return }
        editor.expires = System.currentTimeMillis() + 300_000L
        when (action) {
            "add" -> {
                val kind = when (arguments.getOrNull(2)?.lowercase()) {
                    "bars" -> VisualizerKind.BARS
                    "speaker" -> VisualizerKind.SPEAKER
                    else -> { message(player, "Usage: /disc visualizer add <bars|speaker>"); return }
                }
                val id = visualizers.add(editor.block, kind)
                if (id == null) message(player, "A jukebox supports up to eight visualizers.") else select(editor, id)
            }
            "remove" -> {
                visualizers.delete(editor.block, editor.id)
                visualizers.instances(editor.block).firstOrNull()?.let { select(editor, it.id) }
                    ?: finish(player, "Last visualizer removed. Editing finished.")
            }
            "next" -> cycle(editor)
            "type" -> visualizers.changeKind(editor.block, editor.id)
            "toggle" -> visualizers.toggle(editor.block, editor.id)
            "reset" -> { editor.grabbed = false; visualizers.resetLayout(editor.block, editor.id) }
            "rotate" -> rotate(editor)
            else -> message(player, "Use edit, add bars|speaker, remove, next, type, toggle, reset, rotate, or done.")
        }
    }

    private fun cycle(editor: Editor) {
        val specs = visualizers.instances(editor.block)
        if (specs.isNotEmpty()) select(editor, specs[(specs.indexOfFirst { it.id == editor.id } + 1) % specs.size].id)
    }
    private fun rotate(editor: Editor) {
        val spec = visualizers.instances(editor.block).firstOrNull { it.id == editor.id } ?: return
        visualizers.setFacing(editor.block, editor.id, VisualizerFacing.entries[(spec.facing.ordinal + 1) % 4])
    }
    private fun valid(player: Player, editor: Editor) = player.isOnline && checkCanExecute(player) &&
        player.gameMode != GameMode.SPECTATOR && player.world == editor.block.world &&
        player.location.distanceSquared(editor.block.location) <= 1024 &&
        player.inventory.itemInMainHand.type == Material.STICK &&
        editor.block.world.isChunkLoaded(editor.block.x shr 4, editor.block.z shr 4) &&
        editor.block.type == Material.JUKEBOX && System.currentTimeMillis() < editor.expires

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.clickedBlock != null && event.useInteractedBlock() == Event.Result.DENY) return
        val player = event.player
        val editor = editors[player.uniqueId] ?: return
        if (!valid(player, editor)) { finish(player); return }
        val right = event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
        val left = event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
        if (!right && !left) return
        event.isCancelled = true
        editor.expires = System.currentTimeMillis() + 300_000L
        if (right) {
            if (player.isSneaking) { finish(player); return }
            val center = if (editor.hologram) holograms?.getDisplayLocation(editor.block)?.toVector()
                else visualizers.instances(editor.block).firstOrNull { it.id == editor.id }?.let { anchor(editor.block, it) }
            if (center != null) grab(player, editor, center)
        } else if (editor.hologram) {
            // Left click releases the hologram without changing its position.
            editor.grabbed = false
            holograms?.saveOffsets()
        } else if (player.isSneaking) cycle(editor) else {
            val eye = player.eyeLocation.toVector()
            val direction = player.eyeLocation.direction
            val aimed = visualizers.instances(editor.block).mapNotNull { spec ->
                val delta = anchor(editor.block, spec).add(Vector(0.0, 0.65, 0.0)).subtract(eye)
                val distance = delta.dot(direction)
                val radius = if (spec.kind == VisualizerKind.BARS) 1.4 * spec.layout.width else spec.layout.width.toDouble()
                if (distance > 0 && delta.clone().subtract(direction.clone().multiply(distance)).lengthSquared() <= radius * radius) spec to distance else null
            }.minByOrNull { it.second }?.first
            if (aimed == null) cycle(editor) else select(editor, aimed.id)
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onScroll(event: PlayerItemHeldEvent) {
        val editor = editors[event.player.uniqueId] ?: return
        if (!valid(event.player, editor)) { finish(event.player); return }
        event.isCancelled = true
        val delta = if ((event.newSlot - event.previousSlot + 9) % 9 <= 4) 0.25f else -0.25f
        if (editor.hologram || editor.grabbed) editor.distance = (editor.distance + delta).coerceIn(1.0, 24.0)
        else visualizers.adjustLayout(editor.block, editor.id, dw = delta, dh = if (event.player.isSneaking) 0f else delta)
        editor.expires = System.currentTimeMillis() + 300_000L
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        val editor = editors[event.player.uniqueId] ?: return
        if (!valid(event.player, editor)) { finish(event.player); return }
        event.isCancelled = true
        if (!editor.hologram) rotate(editor)
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (editors.containsKey(event.player.uniqueId)) event.isCancelled = true
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { finish(event.player) }

    fun start() {
        updater = scope.launch {
            while (isActive) {
                for ((uuid, editor) in editors) suspendSyncAt(editor.block.location, voiceServer, plugin) {
                    val player = Bukkit.getPlayer(uuid)
                    if (player == null) {
                        if (editors.remove(uuid, editor)) {
                            if (editor.hologram) holograms?.let { it.saveOffsets(); it.setEditing(editor.block, false) } else visualizers.setEditing(editor.block, null)
                        }
                        return@suspendSyncAt
                    }
                    if (!valid(player, editor)) { finish(player); return@suspendSyncAt }
                    if (editor.hologram) {
                        val display = holograms ?: return@suspendSyncAt
                        if (editor.grabbed) {
                            val point = player.eyeLocation.toVector().add(player.eyeLocation.direction.multiply(editor.distance)).add(editor.grabOffset)
                            val base = JukeboxProgressDisplay.getBaseDisplayLocation(editor.block).toVector()
                            val offset = point.subtract(base)
                            fun snap(value: Double) = (if (player.isSneaking) round(value * 4) / 4 else value).coerceIn(-24.0, 24.0)
                            display.setOffset(editor.block, snap(offset.x), snap(offset.y), snap(offset.z), persist = false)
                        }
                        player.sendActionBar(Component.text("Hologram · ${if (editor.grabbed) "MOVING · scroll: distance · sneak: snap" else "Right-click to grab"} · sneak-right-click: finish", NamedTextColor.AQUA))
                        return@suspendSyncAt
                    }
                    val spec = visualizers.instances(editor.block).firstOrNull { it.id == editor.id }
                    if (spec == null) { finish(player); return@suspendSyncAt }
                    if (editor.grabbed) {
                        val point = player.eyeLocation.toVector().add(player.eyeLocation.direction.multiply(editor.distance)).add(editor.grabOffset)
                        val base = anchor(editor.block, spec).subtract(Vector(spec.layout.x.toDouble(), spec.layout.y.toDouble(), spec.layout.z.toDouble()))
                        val relative = point.subtract(base)
                        fun snap(value: Double) = if (player.isSneaking) (round(value * 4) / 4).toFloat() else value.toFloat()
                        visualizers.adjustLayout(editor.block, editor.id, dx = snap(relative.x) - spec.layout.x,
                            dy = snap(relative.y) - spec.layout.y, dz = snap(relative.z) - spec.layout.z)
                    }
                    player.sendActionBar(Component.text("#${spec.id} ${spec.kind.label} · ${if (editor.grabbed) "MOVING" else "Selected"} · ${spec.facing} · ${spec.layout.width}×${spec.layout.height} · sneak-right-click to finish", NamedTextColor.AQUA))
                }
                delay(100)
            }
        }
    }
    fun shutdown() {
        updater?.cancel()
        editors.values.forEach { if (it.hologram) holograms?.let { display -> display.saveOffsets(); display.setEditing(it.block, false) } else visualizers.setEditing(it.block, null) }
        editors.clear()
    }
    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> = when (arguments.size) {
        2 -> listOf("edit", "add", "remove", "next", "type", "toggle", "reset", "rotate", "done").filter { it.startsWith(arguments[1], true) }
        3 -> if (arguments[1] == "add") listOf("bars", "speaker").filter { it.startsWith(arguments[2], true) } else emptyList()
        else -> emptyList()
    }
}
