package su.plo.voice.discs.event

import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.visualizer.*
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** All instances on a jukebox share one spectrum analysis and viewer check. */
class JukeboxVisualizer(private val jukeboxes: JukeboxEventListener) : Listener, PluginKoinComponent {
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private class Display(val entities: List<Entity>, val kind: VisualizerKind,
        val heights: FloatArray = FloatArray(16), val speaker: SpeakerEnvelope = SpeakerEnvelope(), var spec: VisualizerInstance? = null, var glowing: Boolean = false)
    private class Group(val displays: MutableMap<Int, Display> = mutableMapOf(), var controls: PlaybackControls? = null) {
        var snapshot: List<VisualizerInstance>? = null
        var editingId: Int? = null
        var specs: List<VisualizerInstance> = emptyList()
        var visible = false
        var visibilityChecked = Long.MIN_VALUE
    }
    private val emptyLevels = FloatArray(16)
    private val groups = ConcurrentHashMap<Block, Group>()
    private val settings = ConcurrentHashMap<Block, VisualizerInstances>()
    private val editing = ConcurrentHashMap<Block, Int>()
    fun setEditing(block: Block, id: Int?) {
        if (id == null) editing.remove(block) else editing[block] = id
    }
    private val type = runCatching { EntityType.valueOf("BLOCK_DISPLAY") }.getOrNull()
    private var updater: Job? = null
    val supported get() = type != null
    private fun collection(block: Block) = settings.computeIfAbsent(block) { VisualizerInstances() }
    fun restore(block: Block, instances: List<VisualizerInstance>) { remove(block); collection(block).restore(instances) }
    fun clearSettings(block: Block) { remove(block); settings.remove(block) }
    fun instances(block: Block) = collection(block).snapshot
    fun add(block: Block, kind: VisualizerKind) = collection(block).add(kind)
    fun delete(block: Block, id: Int) { collection(block).remove(id) }
    fun toggle(block: Block, id: Int) { collection(block).update(id) { it.copy(enabled = !it.enabled) } }
    fun setFacing(block: Block, id: Int, facing: VisualizerFacing) { collection(block).update(id) { it.copy(facing = facing) } }
    fun changeKind(block: Block, id: Int) { collection(block).update(id) { it.copy(kind = VisualizerKind.entries[(it.kind.ordinal + 1) % VisualizerKind.entries.size]) } }
    fun adjustLayout(block: Block, id: Int, dx: Float = 0f, dy: Float = 0f, dz: Float = 0f, dw: Float = 0f, dh: Float = 0f) {
        collection(block).update(id) { it.copy(layout = it.layout.adjust(dx, dy, dz, dw, dh)) }
    }
    fun resetLayout(block: Block, id: Int) { collection(block).update(id) { it.copy(layout = VisualizerLayout(), facing = VisualizerFacing.SOUTH) } }

    fun start() {
        if (!supported) return
        updater = scope.launch {
            while (isActive) {
                val blocks = HashSet(groups.keys)
                blocks.addAll(editing.keys)
                for (block in jukeboxes.controlsByBlock.keys)
                    if (settings[block]?.snapshot?.any { it.enabled } == true) blocks.add(block)
                for (block in blocks) suspendSyncAt(block.location, voiceServer, plugin) {
                    if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return@suspendSyncAt
                    val controls = jukeboxes.controlsByBlock[block]
                    val group = groups.computeIfAbsent(block) { Group() }
                    val snapshot = settings[block]?.snapshot.orEmpty()
                    val editingId = editing[block]
                    if (group.snapshot !== snapshot || group.editingId != editingId) {
                        group.snapshot = snapshot
                        group.editingId = editingId
                        group.specs = snapshot.filter { it.enabled || editingId != null }
                        val activeIds = group.specs.map { it.id }.toSet()
                        val obsolete = group.displays.keys.filterNot { it in activeIds }
                        obsolete.forEach { group.displays.remove(it)?.entities?.forEach(Entity::remove) }
                    }
                    val specs = group.specs
                    if (specs.isEmpty() || (controls == null && !editing.containsKey(block)) || block.type != Material.JUKEBOX) {
                        remove(block)
                        return@suspendSyncAt
                    }
                    if (group.controls !== controls) group.controls?.setVisualizing(false)
                    group.controls = controls
                    val now = System.nanoTime()
                    if (group.visibilityChecked == Long.MIN_VALUE || now - group.visibilityChecked >= 250_000_000L) {
                        group.visible = su.plo.voice.discs.utils.PlayerAudience.hasNearby(
                            block.world.name, block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), 32.0)
                        group.visibilityChecked = now
                    }
                    controls?.setVisualizing(group.visible)
                    if (!group.visible) return@suspendSyncAt
                    val levels = controls?.spectrum?.levels ?: emptyLevels
                    var bass = 0f
                    for (i in 0 until minOf(5, levels.size)) bass = maxOf(bass, levels[i])
                    for (spec in specs) {
                        var display = group.displays[spec.id]
                        if (display?.kind != spec.kind || display.entities.any { !it.isValid }) {
                            display?.entities?.forEach(Entity::remove)
                            display = create(block, spec)
                            group.displays[spec.id] = display
                        }
                        if (spec.kind == VisualizerKind.SPEAKER) {
                            val pulse = display.speaker.update(if (controls?.paused != false) 0f else bass)
                            val size = 0.65f + pulse * 0.8f
                            if (display.spec != spec || abs(display.heights[0] - size) > 0.01f) {
                                transform(display.entities[0], spec, 0, size, pulse)
                                display.heights[0] = size
                            }
                        } else for (index in 0 until 16) {
                            val target = if (controls == null && editing.containsKey(block)) 0.6f else if (controls?.paused != false) 0.05f else 0.05f + levels[index] * 1.8f
                            val height = max(target, display.heights[index] * 0.55f)
                            if (display.spec != spec || abs(display.heights[index] - height) > 0.01f) {
                                transform(display.entities[index], spec, index, height)
                                display.heights[index] = height
                            }
                        }
                        val glowing = editingId == spec.id
                        if (display.glowing != glowing) {
                            display.entities.forEach { it.isGlowing = glowing }
                            display.glowing = glowing
                        }
                        display.spec = spec
                    }
                }
                delay(50)
            }
        }
    }

    private fun create(block: Block, spec: VisualizerInstance): Display {
        val entities = mutableListOf<Entity>()
        try {
            val y = if (block.getRelative(0, 1, 0).state is Chest) 2.1 else 1.1
            repeat(if (spec.kind == VisualizerKind.BARS) 16 else 1) { index ->
                val entity = block.world.spawnEntity(block.location.add(0.5, y, 0.5), type!!)
                entities += entity
                entity.isPersistent = false
                val material = if (spec.kind == VisualizerKind.SPEAKER) Material.GRAY_CONCRETE else Material.valueOf(
                    listOf("LIME_CONCRETE", "CYAN_CONCRETE", "BLUE_CONCRETE", "PURPLE_CONCRETE")[index / 4])
                entity.javaClass.getMethod("setBlock", org.bukkit.block.data.BlockData::class.java).invoke(entity, Bukkit.createBlockData(material))
                entity.javaClass.getMethod("setInterpolationDuration", Int::class.javaPrimitiveType).invoke(entity, 1)
                transform(entity, spec, index, if (spec.kind == VisualizerKind.SPEAKER) 0.65f else 0.05f)
            }
            return Display(entities, spec.kind)
        } catch (e: Exception) { entities.forEach(Entity::remove); throw e }
    }

    private val vector by lazy { Class.forName("org.joml.Vector3f") }
    private val quaternion by lazy { Class.forName("org.joml.Quaternionf") }
    private val transformation by lazy { Class.forName("org.bukkit.util.Transformation") }
    private val vectorConstructor by lazy { vector.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType) }
    private val identity by lazy { quaternion.getConstructor() }
    private val rotateY by lazy { quaternion.getMethod("rotateY", Float::class.javaPrimitiveType) }
    private val transformationConstructor by lazy { transformation.getConstructor(vector, quaternion, vector, quaternion) }
    private val setDelay by lazy { Class.forName("org.bukkit.entity.Display").getMethod("setInterpolationDelay", Int::class.javaPrimitiveType) }
    private val setTransformation by lazy { Class.forName("org.bukkit.entity.Display").getMethod("setTransformation", transformation) }

    private fun transform(entity: Entity, spec: VisualizerInstance, index: Int, height: Float, pulse: Float = 0f) {
        val layout = spec.layout
        val speaker = spec.kind == VisualizerKind.SPEAKER
        val width = if (speaker) height * layout.width else 0.12f * layout.width
        val tall = height * layout.height
        val depth = if (speaker) 0.08f + pulse * 0.12f else width
        val x = if (speaker) -width / 2 else (index - 7.5f) * 0.16f * layout.width - width / 2
        val y = if (speaker) 0.65f - tall / 2 else 0f
        val z = 0.4f - depth / 2
        val angle = spec.facing.degrees * PI.toFloat() / 180f
        val cosine = when (spec.facing) { VisualizerFacing.SOUTH -> 1f; VisualizerFacing.NORTH -> -1f; else -> 0f }
        val sine = when (spec.facing) { VisualizerFacing.EAST -> 1f; VisualizerFacing.WEST -> -1f; else -> 0f }
        val rotation = identity.newInstance().also { rotateY.invoke(it, angle) }
        val value = transformationConstructor.newInstance(
            vectorConstructor.newInstance(layout.x + x * cosine + z * sine, layout.y + y,
                layout.z - x * sine + z * cosine), rotation,
            vectorConstructor.newInstance(width, tall, depth), identity.newInstance())
        setDelay.invoke(entity, 0)
        setTransformation.invoke(entity, value)
    }

    private fun remove(block: Block) {
        groups.remove(block)?.let { group ->
            group.controls?.setVisualizing(false)
            group.displays.values.flatMap { it.entities }.forEach(Entity::remove)
        }
        jukeboxes.controlsByBlock[block]?.setVisualizing(false)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        groups.keys.filter { it.inChunk(event.chunk) }.forEach(::remove)
    }
    fun shutdown() { updater?.cancel(); groups.keys.toList().forEach(::remove); settings.clear() }
}
