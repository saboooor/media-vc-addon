package su.plo.voice.discs.event

import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.PlaybackDialogView
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class HologramOffset(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
)

data class HologramVisibility(val main: Boolean = true, val queue: Boolean = true)

/** Transient playback and queue displays, updated on each jukebox's owning thread. */
class JukeboxProgressDisplay(private val jukeboxes: JukeboxEventListener) : Listener, PluginKoinComponent {
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    var queueView: (Block) -> su.plo.voice.discs.command.subcommand.PlaylistCommand.QueueView? = { null }
    private val queueDisplays = ConcurrentHashMap<Block, Entity>()
    private val queueTexts = ConcurrentHashMap<Block, Component>()
    private val visibility = ConcurrentHashMap<String, HologramVisibility>()
    private val visibilityFile get() = File(plugin.dataFolder, "hologram_visibility.json")
    fun visibility(block: Block) = visibility[blockKey(block)] ?: HologramVisibility()
    fun setVisibility(block: Block, value: HologramVisibility) {
        visibility[blockKey(block)] = value
        saveVisibility()
    }
    fun clearVisibility(block: Block) {
        visibility.remove(blockKey(block))
        saveVisibility()
    }
    private fun saveVisibility() {
        val snapshot = visibility.toMap()
        su.plo.voice.discs.utils.SettingsFileWriter.shared.submit(visibilityFile, { gson.toJson(snapshot) }) {
            plugin.slF4JLogger.error("Failed to save hologram visibility", it)
        }
    }
    private val displays = ConcurrentHashMap<Block, Entity>()
    private val displayType = runCatching { EntityType.valueOf("TEXT_DISPLAY") }.getOrNull()
    private var updater: Job? = null
    private val editing = ConcurrentHashMap.newKeySet<Block>()
    private data class TextState(val title: String?, val paused: Boolean, val seconds: Long,
        val durationSeconds: Long, val live: Boolean, val loading: Boolean, val percent: Int, val filled: Int)
    private val textStates = ConcurrentHashMap<Block, Pair<Entity, TextState>>()
    private val textMethod by lazy { Class.forName("org.bukkit.entity.TextDisplay").getMethod("text", Component::class.java) }
    val supported get() = displayType != null

    fun setEditing(block: Block, active: Boolean) {
        if (active) editing.add(block) else editing.remove(block)
    }

    fun start() {
        if (displayType == null) return
        updater = scope.launch {
            while (isActive) {
                val blocks = HashSet(displays.keys)
                blocks.addAll(queueDisplays.keys)
                blocks.addAll(jukeboxes.controlsByBlock.keys)
                blocks.addAll(editing)
                for (block in blocks) {
                    suspendSyncAt(block.location, voiceServer, plugin) {
                        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) {
                            displays.remove(block)
                            queueDisplays.remove(block)
                            queueTexts.remove(block)
                            textStates.remove(block)
                            return@suspendSyncAt
                        }
                        val controls = jukeboxes.controlsByBlock[block]
                        if ((controls == null && block !in editing) || block.type != Material.JUKEBOX) {
                            displays.remove(block)?.remove()
                            queueDisplays.remove(block)?.remove()
                            queueTexts.remove(block)
                            textStates.remove(block)
                            return@suspendSyncAt
                        }
                        updateQueueDisplay(block)
                        if (!visibility(block).main && block !in editing) {
                            displays.remove(block)?.remove()
                            textStates.remove(block)
                            return@suspendSyncAt
                        }
                        val targetLoc = getDisplayLocation(block)
                        val display = displays[block]?.takeIf { it.isValid } ?: create(block, targetLoc).also { displays[block] = it }
                        if (display.location.distanceSquared(targetLoc) > 0.001) {
                            display.teleport(targetLoc)
                        }
                        val position = controls?.position ?: 0
                        val duration = controls?.duration ?: 0
                        val live = controls?.isStream == true
                        val state = TextState(controls?.displayTitle, controls?.paused == true,
                            position / 1000, duration / 1000, live, duration <= 0,
                            if (!live && duration > 0) (position.toDouble() / duration * 100).toInt().coerceIn(0, 100) else 0,
                            if (!live && duration > 0) PlaybackDialogView.filledSegments(position, duration) else 0)
                        val previous = textStates[block]
                        if (previous?.first !== display || previous.second != state) {
                            val text = if (controls == null) Component.text("Playback hologram\nPreview", NamedTextColor.AQUA)
                                else Component.text(state.title!!, NamedTextColor.GOLD)
                                    .append(Component.text(if (state.paused) "\nPaused\n" else "\n", NamedTextColor.GRAY))
                                    .append(PlaybackDialogView.progress(position, duration, live))
                            textMethod.invoke(display, text)
                            textStates[block] = display to state
                        }
                    }
                }
                delay(250)
            }
        }
    }

    private fun updateQueueDisplay(block: Block) {
        val queue = queueView(block)
        if (!visibility(block).queue || queue == null || queue.items.isEmpty()) {
            queueDisplays.remove(block)?.remove()
            queueTexts.remove(block)
            return
        }
        val first = queue.currentIndex.coerceAtLeast(0)
        val shown = queue.items.withIndex().drop(first).take(5)
        var text = Component.text("Queue (${queue.items.size} tracks)", NamedTextColor.AQUA)
        for ((index, item) in shown) {
            text = text.append(Component.text("\n${if (index == queue.currentIndex) "▶" else "•"} ${index + 1}. ${item.title.take(60)}",
                if (index == queue.currentIndex) NamedTextColor.GOLD else NamedTextColor.WHITE))
        }
        val remaining = queue.items.size - first - shown.size
        if (remaining > 0) text = text.append(Component.text("\n+$remaining more", NamedTextColor.GRAY))
        val target = getDisplayLocation(block).add(0.0, 1.4, 0.0)
        val display = queueDisplays[block]?.takeIf { it.isValid }
            ?: create(block, target).also { queueDisplays[block] = it; queueTexts.remove(block) }
        if (display.location.distanceSquared(target) > 0.001) display.teleport(target)
        if (queueTexts[block] != text) {
            textMethod.invoke(display, text)
            queueTexts[block] = text
        }
    }

    fun getDisplayLocation(block: Block): Location {
        val base = Companion.getBaseDisplayLocation(block)
        val offset = offsets[blockKey(block)] ?: return base
        return base.clone().add(offset.x, offset.y, offset.z)
    }

    private val offsets = ConcurrentHashMap<String, HologramOffset>()
    private val offsetsFile: File
        get() = File(plugin.dataFolder, "hologram_offsets.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        loadOffsets()
        try {
            if (visibilityFile.exists()) {
                val type = object : TypeToken<Map<String, HologramVisibility>>() {}.type
                val loaded: Map<String, HologramVisibility>? = gson.fromJson(visibilityFile.readText(), type)
                loaded?.let { visibility.putAll(it) }
            }
        } catch (e: Exception) {
            plugin.slF4JLogger.error("Failed to load hologram visibility", e)
        }
    }

    fun loadOffsets() {
        try {
            val file = offsetsFile
            if (!file.exists()) return
            val json = file.readText()
            val type = object : TypeToken<Map<String, HologramOffset>>() {}.type
            val loaded: Map<String, HologramOffset> = gson.fromJson(json, type) ?: return
            offsets.clear()
            offsets.putAll(loaded)
        } catch (e: Exception) {
            plugin.slF4JLogger.error("Failed to load hologram offsets from $offsetsFile", e)
        }
    }

    fun saveOffsets() {
        val snapshot = offsets.mapValues { (_, offset) -> offset.copy() }
        su.plo.voice.discs.utils.SettingsFileWriter.shared.submit(offsetsFile, { gson.toJson(snapshot) }) {
            plugin.slF4JLogger.error("Failed to save hologram offsets to $offsetsFile", it)
        }
    }

    private fun blockKey(block: Block): String =
        "${block.world.name}:${block.x}:${block.y}:${block.z}"

    fun getOffset(block: Block): HologramOffset {
        return offsets[blockKey(block)] ?: HologramOffset()
    }

    fun setOffset(block: Block, x: Double, y: Double, z: Double, persist: Boolean = true) {
        val key = blockKey(block)
        val roundedX = Math.round(x * 100.0) / 100.0
        val roundedY = Math.round(y * 100.0) / 100.0
        val roundedZ = Math.round(z * 100.0) / 100.0
        val current = offsets[key]
        if ((current?.x ?: 0.0) == roundedX && (current?.y ?: 0.0) == roundedY && (current?.z ?: 0.0) == roundedZ) {
            // A command may be committing an earlier, unsaved drag position.
            if (persist) saveOffsets()
            return
        }
        if (roundedX == 0.0 && roundedY == 0.0 && roundedZ == 0.0) {
            offsets.remove(key)
        } else {
            offsets[key] = HologramOffset(roundedX, roundedY, roundedZ)
        }
        if (persist) saveOffsets()
        teleportDisplay(block)
    }

    fun adjustOffset(block: Block, dx: Double, dy: Double, dz: Double): HologramOffset {
        val current = getOffset(block)
        val newX = current.x + dx
        val newY = current.y + dy
        val newZ = current.z + dz
        setOffset(block, newX, newY, newZ)
        return getOffset(block)
    }

    fun resetOffset(block: Block) {
        offsets.remove(blockKey(block))
        saveOffsets()
        teleportDisplay(block)
    }

    fun teleportDisplay(block: Block) {
        val display = displays[block]?.takeIf { it.isValid } ?: return
        val target = getDisplayLocation(block)
        display.teleport(target)
    }

    companion object {
        fun getDisplayLocation(block: Block): Location = getBaseDisplayLocation(block)

        fun getBaseDisplayLocation(block: Block): Location {
            val aboveBlock = block.getRelative(0, 1, 0)
            val chestState = aboveBlock.state as? Chest
            if (chestState == null) {
                return block.location.add(0.5, 1.35, 0.5)
            }

            // Try getting both halves from DoubleChest inventory holder
            val doubleChest = chestState.inventory.holder as? DoubleChest
            if (doubleChest != null) {
                val leftBlock = (doubleChest.leftSide as? Chest)?.block
                val rightBlock = (doubleChest.rightSide as? Chest)?.block
                if (leftBlock != null && rightBlock != null) {
                    val midX = (leftBlock.x + rightBlock.x) / 2.0 + 0.5
                    val midZ = (leftBlock.z + rightBlock.z) / 2.0 + 0.5
                    return Location(block.world, midX, block.y + 2.35, midZ)
                }
            }

            // Fallback to block data if holder didn't resolve both halves
            val chestData = aboveBlock.blockData as? org.bukkit.block.data.type.Chest
            if (chestData != null && chestData.type != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                val facing = chestData.facing
                val isLeft = chestData.type == org.bukkit.block.data.type.Chest.Type.LEFT
                val otherFace = when (facing) {
                    BlockFace.NORTH -> if (isLeft) BlockFace.EAST else BlockFace.WEST
                    BlockFace.SOUTH -> if (isLeft) BlockFace.WEST else BlockFace.EAST
                    BlockFace.EAST -> if (isLeft) BlockFace.SOUTH else BlockFace.NORTH
                    BlockFace.WEST -> if (isLeft) BlockFace.NORTH else BlockFace.SOUTH
                    else -> null
                }
                if (otherFace != null) {
                    val otherBlock = aboveBlock.getRelative(otherFace)
                    val midX = (aboveBlock.x + otherBlock.x) / 2.0 + 0.5
                    val midZ = (aboveBlock.z + otherBlock.z) / 2.0 + 0.5
                    return Location(block.world, midX, block.y + 2.35, midZ)
                }
            }

            return block.location.add(0.5, 2.35, 0.5)
        }
    }

    private fun applyScale(display: Entity, scale: Float) {
        runCatching {
            val transfClass = Class.forName("org.bukkit.util.Transformation")
            val vec3fClass = Class.forName("org.joml.Vector3f")
            val quatClass = Class.forName("org.joml.Quaternionf")
            val vec3fCtor = vec3fClass.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val translation = vec3fCtor.newInstance(0f, 0f, 0f)
            val scaleVec = vec3fCtor.newInstance(scale, scale, scale)
            val quat = quatClass.getConstructor().newInstance()
            val transfCtor = transfClass.getConstructor(vec3fClass, quatClass, vec3fClass, quatClass)
            val transf = transfCtor.newInstance(translation, quat, scaleVec, quat)
            display.javaClass.getMethod("setTransformation", transfClass).invoke(display, transf)
        }
    }

    private fun create(block: Block, location: Location = getDisplayLocation(block)): Entity {
        val display = block.world.spawnEntity(location, displayType!!)
        try {
            display.isPersistent = false
            val billboardClass = Class.forName("org.bukkit.entity.Display\$Billboard")
            val center = billboardClass.enumConstants.first { (it as Enum<*>).name == "CENTER" }
            display.javaClass.getMethod("setBillboard", billboardClass).invoke(display, center)
            display.javaClass.getMethod("setShadowed", Boolean::class.javaPrimitiveType).invoke(display, true)
            display.javaClass.getMethod("setLineWidth", Int::class.javaPrimitiveType).invoke(display, 260)
            applyScale(display, 0.5f)
            runCatching {
                display.javaClass.getMethod("setDefaultBackground", Boolean::class.javaPrimitiveType).invoke(display, false)
            }
            runCatching {
                val colorClass = Class.forName("org.bukkit.Color")
                val transparent = runCatching {
                    colorClass.getMethod("fromARGB", Int::class.javaPrimitiveType).invoke(null, 0)
                }.getOrNull() ?: colorClass.getMethod(
                    "fromARGB",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(null, 0, 0, 0, 0)
                display.javaClass.getMethod("setBackgroundColor", colorClass).invoke(display, transparent)
            }
            runCatching {
                display.javaClass.getMethod("setSeeThrough", Boolean::class.javaPrimitiveType).invoke(display, false)
            }
            return display
        } catch (e: Exception) {
            display.remove()
            throw e
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        displays.keys.filter { it.inChunk(event.chunk) }.forEach { displays.remove(it)?.remove(); textStates.remove(it) }
        queueDisplays.keys.filter { it.inChunk(event.chunk) }.forEach { queueDisplays.remove(it)?.remove(); queueTexts.remove(it) }
    }

    fun shutdown() {
        updater?.cancel()
        saveOffsets()
        displays.values.forEach { it.remove() }
        displays.clear()
        queueDisplays.values.forEach { it.remove() }
        queueDisplays.clear()
        queueTexts.clear()
        textStates.clear()
        editing.clear()
    }
}
