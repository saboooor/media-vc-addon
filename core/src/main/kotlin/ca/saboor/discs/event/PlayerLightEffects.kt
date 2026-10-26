package ca.saboor.discs.event

import ca.saboor.discs.utils.PlayerAudience
import ca.saboor.discs.visualizer.LightBlockFrame
import com.github.retrooper.packetevents.util.Vector3i
import ca.saboor.discs.settings.LightEffectPreferences
import java.io.File
import ca.saboor.discs.dialog.PaperPlaybackDialog
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.wrapper.play.server.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Packet threads read immutable block masks and player snapshots only. */
class PlayerLightEffects(private val plugin: JavaPlugin, private val refresh: (Player) -> Unit) : PacketListenerAbstract(), Listener {
    private val legacyKey = NamespacedKey(plugin, "show_light_effects")
    private val preferences = LightEffectPreferences(File(plugin.dataFolder, "light_effect_preferences.json")) {
        plugin.slF4JLogger.error("Could not load or save player light-effects preferences", it)
    }
    private data class Viewer(val world: String, val minSection: Int, val enabled: Boolean)
    private val viewers = ConcurrentHashMap<UUID, Viewer>()
    private val warned = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var masks = LightEffectMaskSnapshot(emptyList())
    @Volatile private var frame = LightBlockFrame(emptyMap())
    fun enabled(player: Player) = viewers[player.uniqueId]?.enabled == true
    private fun update(player: Player) {
        preferences.migrate(player.uniqueId,
            player.persistentDataContainer.get(legacyKey, PersistentDataType.BYTE)?.toInt()?.let { it == 1 })
        val minY = runCatching { player.world.javaClass.getMethod("getMinHeight").invoke(player.world) as Int }.getOrDefault(0)
        viewers[player.uniqueId] = Viewer(player.world.uid.toString(), minY shr 4,
            preferences.choice(player.uniqueId) == true)
    }
    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.onlinePlayers.forEach(::update)
        PacketEvents.getAPI().eventManager.registerListener(this)
    }
    fun shutdown() { PacketEvents.getAPI().eventManager.unregisterListener(this); org.bukkit.event.HandlerList.unregisterAll(this); viewers.clear(); warned.clear() }
    fun setLinks(links: Collection<JukeboxBassLamps.Link>) {
        masks = LightEffectMaskSnapshot(links)
        frame = LightBlockFrame(frame.blocks.filterKeys { it in masks.blocks })
    }

    internal fun release(link: JukeboxBassLamps.Link) {
        val positions = setOf(link.lamp, link.lamp.copy(y = link.lamp.y + 1))
        frame = LightBlockFrame(frame.blocks.filterKeys { it !in positions })
    }

    private val reportedFrameFailure = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun publishFrame(blocks: Map<JukeboxBassLamps.Position, String>) {
        val completed = LightBlockFrame(blocks.filterKeys { it in masks.blocks })
        frame = completed
        val manager = PacketEvents.getAPI().playerManager
        for ((section, entries) in completed.sections) {
            val viewers = PlayerAudience.nearby(section.world, section.x * 16.0 + 8, section.y * 16.0 + 8, section.z * 16.0 + 8, 96.0)
                .filter { enabled(it) && PlayerAudience.canReceiveScreen(it) }
            if (viewers.isEmpty()) continue
            val packet = WrapperPlayServerMultiBlockChange(Vector3i(section.x, section.y, section.z), false,
                entries.map { (pos, data) ->
                    WrapperPlayServerMultiBlockChange.EncodedBlock(WrappedBlockState.getByString(data), pos.x, pos.y, pos.z)
                }.toTypedArray())
            // Bypass this listener: the complete frame is already filtered to opted-in players.
            viewers.forEach { player ->
                runCatching { manager.sendPacketSilently(player, packet) }.onFailure { error ->
                    if (reportedFrameFailure.compareAndSet(false, true)) plugin.slF4JLogger.error("Could not send a complete light frame", error)
                }
            }
        }
    }

    fun needsWarning(player: Player) = preferences.choice(player.uniqueId) == null && warned.add(player.uniqueId)
    fun showWarning(player: Player) {
        if (!player.isOnline) return
        val status = if (enabled(player)) "Your saved choice is to show effects." else "Effects are hidden for you until you choose to show them."
        val body = Component.text("Epilepsy warning: nearby music lights can flash rapidly and change colors. These effects may trigger seizures in people with photosensitive epilepsy. $status Your choice affects only you.")
        val buttons = listOf("Hide light effects for me" to "/disc lights hide", "Show light effects for me" to "/disc lights show")
        if (PaperPlaybackDialog.isAvailable()) {
            runCatching { PaperPlaybackDialog.show(player, listOf(body), buttons, title = "Flashing light warning") }.onSuccess { return }
        }
        player.sendMessage(body)
        buttons.forEach { (label, command) -> player.sendMessage(Component.text("[$label]").clickEvent(ClickEvent.runCommand(command))) }
    }
    @EventHandler fun onJoin(event: PlayerJoinEvent) { update(event.player) }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) { update(event.player) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { viewers.remove(event.player.uniqueId); warned.remove(event.player.uniqueId) }
    @EventHandler(ignoreCancelled = true) fun onCommand(event: PlayerCommandPreprocessEvent) {
        val args = event.message.trim().split(Regex("\\s+"))
        if (args.size != 3 || !args[0].equals("/disc", true) || !args[1].equals("lights", true)) return
        val enabled = when (args[2].lowercase()) { "show" -> true; "hide" -> false; else -> return }
        event.isCancelled = true
        preferences.choose(event.player.uniqueId, enabled)
        update(event.player)
        refresh(event.player)
        event.player.sendMessage(Component.text(if (enabled) "Light effects shown for you. Use /disc lights hide to hide them." else "Light effects hidden for you. Use /disc lights show to show them."))
    }
    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled) return
        val viewerId = event.user?.uuid ?: return
        val viewer = viewers[viewerId] ?: return
        val snapshot = masks
        val visibleBlocks = if (viewer.enabled) frame.blocks else snapshot.blocks
        fun state(x: Int, y: Int, z: Int): WrappedBlockState? = visibleBlocks[JukeboxBassLamps.Position(viewer.world, x, y, z)]?.let { WrappedBlockState.getByString(it) }
        when (event.packetType) {
            PacketType.Play.Server.BLOCK_CHANGE -> {
                val packet = WrapperPlayServerBlockChange(event)
                val pos = packet.blockPosition
                state(pos.x, pos.y, pos.z)?.let { packet.blockState = it; event.markForReEncode(true) }
            }
            PacketType.Play.Server.MULTI_BLOCK_CHANGE -> {
                val packet = WrapperPlayServerMultiBlockChange(event)
                packet.blocks.forEach { block -> state(block.x, block.y, block.z)?.let { block.blockId = it.globalId; event.markForReEncode(true) } }
            }
            PacketType.Play.Server.CHUNK_DATA -> {
                val packet = WrapperPlayServerChunkData(event)
                val column = packet.column
                val entries = if (viewer.enabled) visibleBlocks.entries.filter {
                    it.key.world == viewer.world && (it.key.x shr 4) == column.x && (it.key.z shr 4) == column.z
                } else snapshot.chunk(viewer.world, column.x, column.z)
                for ((pos, data) in entries) {
                    val section = column.chunks.getOrNull((pos.y shr 4) - viewer.minSection) ?: continue
                    section.set(pos.x and 15, pos.y and 15, pos.z and 15, WrappedBlockState.getByString(data))
                    event.markForReEncode(true)
                }
            }
            PacketType.Play.Server.UPDATE_LIGHT -> {
                if (viewer.enabled) return
                val packet = WrapperPlayServerUpdateLight(event)
                // Light spills across chunk boundaries. Freeze nearby lighting as well as the lamp chunk.
                if (snapshot.freezesLighting(viewer.world, packet.x, packet.z)) event.isCancelled = true
            }
            else -> Unit
        }
    }
}

/** Index once per link edit, rather than scanning every light for each outgoing packet. */
internal class LightEffectMaskSnapshot(links: Collection<JukeboxBassLamps.Link>) {
    private data class Chunk(val world: String, val x: Int, val z: Int)
    val blocks: Map<JukeboxBassLamps.Position, String> = buildMap {
        for (link in links) {
            put(link.lamp, link.originalData ?: if (link.theatre) "minecraft:light[level=15]" else "minecraft:redstone_lamp[lit=false]")
            link.shutterData?.let { put(link.lamp.copy(y = link.lamp.y + 1), it) }
        }
    }
    private val chunks = blocks.entries.groupBy { Chunk(it.key.world, it.key.x shr 4, it.key.z shr 4) }
    private val frozenChunks = buildSet {
        for (chunk in chunks.keys) for (dx in -1..1) for (dz in -1..1) add(chunk.copy(x = chunk.x + dx, z = chunk.z + dz))
    }
    fun chunk(world: String, x: Int, z: Int) = chunks[Chunk(world, x, z)].orEmpty()
    fun freezesLighting(world: String, x: Int, z: Int) = Chunk(world, x, z) in frozenChunks
}
