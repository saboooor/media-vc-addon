package su.plo.voice.discs.event

import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import su.plo.voice.discs.visualizer.BassLampEnvelope
import java.io.File
import su.plo.voice.discs.utils.PlayerAudience
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Links ordinary player-placed lamps; never creates, consumes, or removes world blocks. */
class JukeboxBassLamps(private val jukeboxes: JukeboxEventListener) : Listener, PluginKoinComponent {
    data class Position(val world: String, val x: Int, val y: Int, val z: Int) {
        fun loadedBlock(): Block? {
            val level = runCatching { Bukkit.getWorld(UUID.fromString(world)) }.getOrNull() ?: return null
            return if (level.isChunkLoaded(x shr 4, z shr 4)) level.getBlockAt(x, y, z) else null
        }
        companion object { fun of(block: Block) = Position(block.world.uid.toString(), block.x, block.y, block.z) }
    }
    data class Link(val lamp: Position, val jukebox: Position)
    private data class Pending(val jukebox: Block, val unlink: Boolean, val expires: Long = System.currentTimeMillis() + 120_000)
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val links = ConcurrentHashMap<Position, Link>()
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val envelopes = ConcurrentHashMap<Position, BassLampEnvelope>()
    private val consumers = ConcurrentHashMap.newKeySet<PlaybackControls>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(plugin.dataFolder, "bass_lamp_links.json")
    private var updater: Job? = null
    private data class ChunkKey(val world: String, val x: Int, val z: Int)
    private data class Index(val version: Long, val sources: Map<Position, List<Link>>, val chunks: Map<ChunkKey, List<Link>>)
    private val linksVersion = AtomicLong()
    @Volatile private var index = Index(-1, emptyMap(), emptyMap())
    private fun linkedIndex(): Index {
        val version = linksVersion.get()
        if (index.version == version) return index
        val snapshot = links.values.toList()
        return Index(version, snapshot.groupBy { it.jukebox }, snapshot.groupBy {
            ChunkKey(it.lamp.world, it.lamp.x shr 4, it.lamp.z shr 4)
        }).also { index = it }
    }

    fun beginLink(player: Player, jukebox: Block, unlink: Boolean = false) {
        pending[player.uniqueId] = Pending(jukebox, unlink)
        player.sendMessage(Component.text("Right-click a placed redstone lamp within 32 blocks of this jukebox to ${if (unlink) "unlink" else "link"} it (2 minutes)."))
    }
    fun linkedPositions(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty().map { it.lamp }
    fun restoreLinks(jukebox: Block, lamps: List<Position>) {
        val source = Position.of(jukebox)
        var changed = false
        for (lamp in lamps) {
            if (lamp.world != source.world) continue
            val distance = (lamp.x.toLong() - source.x).let { it * it } + (lamp.y.toLong() - source.y).let { it * it } + (lamp.z.toLong() - source.z).let { it * it }
            if (distance > 1024) continue
            if (links[lamp]?.jukebox?.let { it != source } == true) continue
            val link = Link(lamp, source)
            if (links.put(lamp, link) != link) changed = true
        }
        if (changed) save()
    }
    fun count(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)]?.size ?: 0

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val request = pending[event.player.uniqueId] ?: return
        val lamp = event.clickedBlock?.takeIf { it.type == Material.REDSTONE_LAMP } ?: return
        if (System.currentTimeMillis() > request.expires) { pending.remove(event.player.uniqueId); return }
        val player = event.player
        if (!(player.hasPermission("pv.addon.discs.control") || player.hasPermission("vc.addon.discs.control")) ||
            !(player.hasPermission("pv.addon.discs.play") || player.hasPermission("vc.addon.discs.play"))) return
        if (lamp.world != request.jukebox.world || lamp.location.distanceSquared(request.jukebox.location) > 32 * 32 ||
            request.jukebox.type != Material.JUKEBOX) {
            player.sendMessage(Component.text("Choose a redstone lamp within 32 blocks of the jukebox."))
            return
        }
        event.isCancelled = true
        pending.remove(player.uniqueId)
        val key = Position.of(lamp)
        if (request.unlink) {
            if (links[key]?.jukebox != Position.of(request.jukebox)) {
                player.sendMessage(Component.text("That lamp is not linked to this jukebox.")); return
            }
            links.remove(key)
            envelopes.remove(key)
            setLit(lamp, false)
        } else {
            links[key] = Link(key, Position.of(request.jukebox))
            envelopes.remove(key)
        }
        save()
        player.sendMessage(Component.text(if (request.unlink) "Bass lamp unlinked." else "Bass lamp linked. It will light up with this jukebox's bass."))
    }

    fun start() {
        if (file.exists()) runCatching {
            gson.fromJson(file.readText(), Array<Link>::class.java)?.forEach { links[it.lamp] = it }
        }.onFailure { plugin.slF4JLogger.error("Could not load bass lamp links", it) }
        linksVersion.incrementAndGet()
        updater = scope.launch {
            var nextMaintenance = 0L
            var validSources: Map<Position, Boolean> = emptyMap()
            while (isActive) {
                val updateStarted = System.nanoTime()
                val snapshot = linkedIndex()
                val maintenance = System.nanoTime() >= nextMaintenance
                if (maintenance) {
                    val valid = mutableMapOf<Position, Boolean>()
                    // Validate each source once on its owning region, never from a lamp region.
                    for ((chunk, sources) in snapshot.sources.keys.groupBy { ChunkKey(it.world, it.x shr 4, it.z shr 4) }) {
                        val world = runCatching { Bukkit.getWorld(UUID.fromString(chunk.world)) }.getOrNull() ?: continue
                        if (!world.isChunkLoaded(chunk.x, chunk.z)) continue
                        val first = sources.first()
                        suspendSyncAt(Location(world, first.x.toDouble(), first.y.toDouble(), first.z.toDouble()), voiceServer, plugin) {
                            for (source in sources) source.loadedBlock()?.let { valid[source] = it.type == Material.JUKEBOX }
                        }
                    }
                    validSources = valid
                    nextMaintenance = System.nanoTime() + 3_000_000_000L
                }
                val sourceControls = jukeboxes.controlsByBlock.entries.associate { Position.of(it.key) to it.value }
                // One coarse audience query per playing jukebox; lamps test immutable positions.
                val audiences = snapshot.sources.keys.filter { sourceControls.containsKey(it) }.associateWith {
                    PlayerAudience.positions(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), 64.0)
                }
                val active = mutableSetOf<PlaybackControls>()
                var dirty = false
                for ((chunk, chunkLinks) in snapshot.chunks) {
                    if (!maintenance && chunkLinks.none { sourceControls.containsKey(it.jukebox) || envelopes.containsKey(it.lamp) }) continue
                    val world = runCatching { Bukkit.getWorld(UUID.fromString(chunk.world)) }.getOrNull() ?: continue
                    if (!world.isChunkLoaded(chunk.x, chunk.z)) continue
                    val first = chunkLinks.first().lamp
                    suspendSyncAt(Location(world, first.x.toDouble(), first.y.toDouble(), first.z.toDouble()), voiceServer, plugin) {
                        for (link in chunkLinks) {
                            if (links[link.lamp] != link) continue
                            val lamp = link.lamp.loadedBlock() ?: continue
                            if (lamp.type != Material.REDSTONE_LAMP || (maintenance && validSources[link.jukebox] == false)) {
                                if (links.remove(link.lamp, link)) { envelopes.remove(link.lamp); dirty = true }
                                setLit(lamp, false)
                                continue
                            }
                            val controls = sourceControls[link.jukebox]
                            val listening = controls != null && audiences[link.jukebox].orEmpty().any {
                                it.within(link.lamp.x.toDouble(), link.lamp.y.toDouble(), link.lamp.z.toDouble(), 32.0)
                            }
                            if (listening) active += controls!!
                            val lit = if (listening && controls?.paused == false)
                                envelopes.computeIfAbsent(link.lamp) { BassLampEnvelope() }.update(
                                    controls.spectrum.levels.let { levels ->
                                        var peak = 0f
                                        for (i in 0 until minOf(5, levels.size)) peak = maxOf(peak, levels[i])
                                        peak
                                    })
                            else { envelopes.remove(link.lamp); false }
                            setLit(lamp, lit)
                        }
                    }
                }
                if (dirty) save()
                active.filterNot { it in consumers }.forEach { it.setVisualizing(true, "bass_lamps") }
                consumers.filterNot { it in active }.forEach { it.setVisualizing(false, "bass_lamps") }
                consumers.clear(); consumers.addAll(active)
                val elapsedMs = (System.nanoTime() - updateStarted) / 1_000_000L
                delay((50L - elapsedMs).coerceAtLeast(1L))
            }
        }
    }

    private fun setLit(block: Block, bass: Boolean) {
        if (block.type != Material.REDSTONE_LAMP) return
        val lit = bass || block.isBlockPowered || block.isBlockIndirectlyPowered
        val data = block.blockData as Lightable
        if (data.isLit != lit) block.setBlockData(data.apply { isLit = lit }, false)
    }
    private fun save() {
        linksVersion.incrementAndGet()
        val snapshot = links.values.toList()
        su.plo.voice.discs.utils.SettingsFileWriter.shared.submit(file, { gson.toJson(snapshot) }) {
            plugin.slF4JLogger.error("Could not save bass lamp links", it)
        }
    }
    private fun unlinkBroken(blocks: Collection<Block>) {
        val broken = blocks.map(Position::of).toSet()
        var changed = false
        for (link in links.values.toList()) if (link.lamp in broken || link.jukebox in broken) {
            links.remove(link.lamp, link); envelopes.remove(link.lamp)
            link.lamp.loadedBlock()?.let { setLit(it, false) }
            changed = true
        }
        if (changed) save()
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (event.block.type == Material.JUKEBOX || event.block.type == Material.REDSTONE_LAMP)
            unlinkBroken(listOf(event.block))
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) { unlinkBroken(event.blockList()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) { unlinkBroken(event.blockList()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        linkedIndex().chunks[ChunkKey(event.world.uid.toString(), event.chunk.x, event.chunk.z)].orEmpty()
            .forEach { it.lamp.loadedBlock()?.let { block -> setLit(block, false) }; envelopes.remove(it.lamp) }
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
    fun shutdown() {
        updater?.cancel()
        save()
        consumers.forEach { it.setVisualizing(false, "bass_lamps") }; consumers.clear()
        links.keys.forEach { it.loadedBlock()?.let { block -> setLit(block, false) } }
        pending.clear(); envelopes.clear()
    }
}
