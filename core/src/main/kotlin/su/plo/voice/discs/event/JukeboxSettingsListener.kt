package su.plo.voice.discs.event

import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.event.*
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.ItemStack
import org.koin.core.component.inject
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.command.subcommand.PlaylistCommand
import su.plo.voice.discs.screen.ScreenManager
import su.plo.voice.discs.settings.SavedJukeboxSettings
import su.plo.voice.discs.utils.PluginKoinComponent
import java.util.concurrent.ConcurrentHashMap

/** Settings travel with the jukebox drop, including a custom drop for creative breaks. */
class JukeboxSettingsListener(
    private val playback: JukeboxEventListener,
    private val playlists: PlaylistCommand,
    private val visualizers: JukeboxVisualizer,
    private val holograms: JukeboxProgressDisplay,
    private val lamps: JukeboxBassLamps,
    private val screens: ScreenManager,
) : Listener, PluginKoinComponent {
    private val scope: CoroutineScope by inject()
    private val pending = ConcurrentHashMap<Block, SavedJukeboxSettings>()

    private fun capture(block: Block): SavedJukeboxSettings {
        val fade = playlists.crossfadeSettings(block)
        return SavedJukeboxSettings(volume = playback.volumeSetting(block), crossfade = fade.first, lastCrossfade = fade.second,
            hologram = holograms.getOffset(block).copy(), visualizers = visualizers.instances(block),
            lamps = lamps.linkedPositions(block), screenName = screens.getScreenForJukebox(block)?.name)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun beforeBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.JUKEBOX) return
        val settings = capture(event.block)
        pending[event.block] = settings
        scope.launch { delay(2000); pending.remove(event.block, settings) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun afterBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.JUKEBOX) return
        if (event.player.gameMode == GameMode.CREATIVE) {
            pending.remove(event.block)?.let { settings ->
                event.block.world.dropItemNaturally(event.block.location.add(0.5, 0.5, 0.5),
                    savedItem(ItemStack(Material.JUKEBOX), settings))
            }
        }
        visualizers.clearSettings(event.block)
        holograms.resetOffset(event.block)
        playlists.clearSettings(event.block)
        playback.clearVolume(event.block)
        screens.getScreenForJukebox(event.block)?.let { screen ->
            screens.stopVideo(screen)
            screen.jukeboxWorld = null; screen.jukeboxX = null; screen.jukeboxY = null; screen.jukeboxZ = null
            screens.saveScreens()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: BlockDropItemEvent) {
        if (event.blockState.type != Material.JUKEBOX) return
        val settings = pending.remove(event.block) ?: return
        event.items.filter { it.itemStack.type == Material.JUKEBOX }.forEach { drop ->
            drop.itemStack = savedItem(drop.itemStack, settings)
        }
    }

    private fun savedItem(item: ItemStack, settings: SavedJukeboxSettings): ItemStack {
        item.editMeta { meta ->
            meta.persistentDataContainer.set(AddonKeys.jukeboxSettingsKey, PersistentDataType.STRING, settings.encode())
            meta.lore((meta.lore() ?: emptyList()) + Component.text("Saved jukebox settings", NamedTextColor.GRAY))
        }
        return item
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.blockPlaced.type != Material.JUKEBOX) return
        val json = event.itemInHand.itemMeta?.persistentDataContainer
            ?.get(AddonKeys.jukeboxSettingsKey, PersistentDataType.STRING) ?: return
        val settings = SavedJukeboxSettings.decode(json) ?: return
        apply(event.blockPlaced, settings)
        write(event.blockPlaced, settings)
    }

    private fun apply(block: Block, settings: SavedJukeboxSettings) {
        playback.restoreVolume(block, settings.volume)
        playlists.restoreCrossfade(block, settings.crossfade, settings.lastCrossfade)
        visualizers.restore(block, settings.visualizers)
        holograms.setOffset(block, settings.hologram.x, settings.hologram.y, settings.hologram.z)
        if (settings.lamps.isNotEmpty()) lamps.restoreLinks(block, settings.lamps)
        settings.screenName?.let { name ->
            val screen = screens.getScreen(name)
            if (screen != null && screen.worldName == block.world.name && (screen.jukeboxWorld == null || screen.isLinkedTo(block))) {
                screens.linkJukebox(name, block)
            }
        }
    }
    private fun write(block: Block, settings: SavedJukeboxSettings) {
        if (block.type != Material.JUKEBOX) return
        val state = block.state as? TileState ?: return
        state.persistentDataContainer.set(AddonKeys.jukeboxSettingsKey, PersistentDataType.STRING, settings.encode())
        state.update(false, false)
    }
    private fun restore(block: Block) {
        val state = block.state as? TileState ?: return
        val json = state.persistentDataContainer.get(AddonKeys.jukeboxSettingsKey, PersistentDataType.STRING) ?: return
        SavedJukeboxSettings.decode(json)?.let { apply(block, it) }
    }
    @EventHandler fun onLoad(event: ChunkLoadEvent) {
        event.chunk.tileEntities.filter { it.type == Material.JUKEBOX }.forEach { restore(it.block) }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        event.chunk.tileEntities.filter { it.type == Material.JUKEBOX }.forEach { write(it.block, capture(it.block)) }
    }
    fun start() {
        Bukkit.getWorlds().flatMap { it.loadedChunks.toList() }.flatMap { it.tileEntities.toList() }
            .filter { it.type == Material.JUKEBOX }.forEach { restore(it.block) }
    }
    fun shutdown() {
        Bukkit.getWorlds().flatMap { it.loadedChunks.toList() }.flatMap { it.tileEntities.toList() }
            .filter { it.type == Material.JUKEBOX }.forEach { write(it.block, capture(it.block)) }
        pending.clear()
    }
}
