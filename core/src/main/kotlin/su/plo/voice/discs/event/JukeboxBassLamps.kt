package su.plo.voice.discs.event

import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
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

    fun beginLink(player: Player, jukebox: Block, unlink: Boolean = false) {
        pending[player.uniqueId] = Pending(jukebox, unlink)
        player.sendMessage(Component.text("Right-click a placed redstone lamp within 32 blocks of this jukebox to ${if (unlink) "unlink" else "link"} it (2 minutes)."))
    }
    fun linkedPositions(jukebox: Block) = links.values.filter { it.jukebox == Position.of(jukebox) }.map { it.lamp }
    fun restoreLinks(jukebox: Block, lamps: List<Position>) {
        val source = Position.of(jukebox)
        for (lamp in lamps) {
            if (lamp.world != source.world) continue
            val distance = (lamp.x.toLong() - source.x).let { it * it } + (lamp.y.toLong() - source.y).let { it * it } + (lamp.z.toLong() - source.z).let { it * it }
            if (distance > 1024) continue
            if (links[lamp]?.jukebox?.let { it != source } == true) continue
            if (lamp.loadedBlock()?.type?.let { it != Material.REDSTONE_LAMP } == true) continue
            links[lamp] = Link(lamp, source)
        }
        save()
    }
    fun count(jukebox: Block) = links.values.count { it.jukebox == Position.of(jukebox) }

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
        updater = scope.launch {
            while (isActive) {
                val active = mutableSetOf<PlaybackControls>()
                for (link in links.values.toList()) {
                    val lamp = link.lamp.loadedBlock() ?: continue
                    suspendSyncAt(lamp.location, voiceServer, plugin) {
                        val source = link.jukebox.loadedBlock()
                        if (lamp.type != Material.REDSTONE_LAMP || (source != null && source.type != Material.JUKEBOX)) {
                            links.remove(link.lamp, link); envelopes.remove(link.lamp); setLit(lamp, false); save()
                            return@suspendSyncAt
                        }
                        val controls = source?.let { jukeboxes.controlsByBlock[it] }
                        val listening = controls != null && lamp.world.getNearbyPlayers(lamp.location, 32.0).isNotEmpty()
                        if (listening) { controls!!.setVisualizing(true, "bass_lamps"); active += controls }
                        val bass = if (listening && controls?.paused == false) controls.spectrum.levels.take(5).max() else 0f
                        val lit = if (listening && controls?.paused == false)
                            envelopes.computeIfAbsent(link.lamp) { BassLampEnvelope() }.update(bass)
                        else { envelopes.remove(link.lamp); false }
                        setLit(lamp, lit)
                    }
                }
                consumers.filterNot { it in active }.forEach { it.setVisualizing(false, "bass_lamps") }
                consumers.clear(); consumers.addAll(active)
                delay(100)
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
        runCatching { file.parentFile.mkdirs(); file.writeText(gson.toJson(links.values.toList())) }
            .onFailure { plugin.slF4JLogger.error("Could not save bass lamp links", it) }
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
    fun onBreak(event: BlockBreakEvent) { unlinkBroken(listOf(event.block)) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) { unlinkBroken(event.blockList()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) { unlinkBroken(event.blockList()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        links.keys.filter { it.world == event.world.uid.toString() && it.x shr 4 == event.chunk.x && it.z shr 4 == event.chunk.z }
            .forEach { it.loadedBlock()?.let { block -> setLit(block, false) }; envelopes.remove(it) }
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
    fun shutdown() {
        updater?.cancel()
        consumers.forEach { it.setVisualizing(false, "bass_lamps") }; consumers.clear()
        links.keys.forEach { it.loadedBlock()?.let { block -> setLit(block, false) } }
        pending.clear(); envelopes.clear()
    }
}
