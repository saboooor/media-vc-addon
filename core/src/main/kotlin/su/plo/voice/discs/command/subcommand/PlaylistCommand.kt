package su.plo.voice.discs.command.subcommand

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.inject
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.api.PlayerBurnEvent
import su.plo.voice.discs.api.PrePlayerBurnEvent
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.item.DiscHelper
import su.plo.voice.discs.utils.extend.*
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Chest inventories are only inspected/edited on their region's server thread. */
class PlaylistCommand(private val jukeboxes: JukeboxEventListener) : SubCommand(), Listener {
    private val discHelper: DiscHelper by inject()
    private class Pending(@Volatile var created: Long = System.currentTimeMillis()) {
        @Volatile var tracks: List<AudioTrack>? = null
    }
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val queues = ConcurrentHashMap<Block, Job>()
    private val jumpTargets = ConcurrentHashMap<Block, Int>()
    private val skipping = ConcurrentHashMap.newKeySet<Block>()
    data class QueueItem(val title: String, val sprite: String)
    data class QueueView(val items: List<QueueItem>, val currentIndex: Int, val crossfadeSeconds: Int)
    private class QueueState(@Volatile var items: List<QueueItem>) {
        @Volatile var currentIndex = 0
        @Volatile var crossfadeSeconds = 3
        var lastCrossfadeSeconds = 3
    }
    private val crossfadeDefaults = ConcurrentHashMap<Block, Pair<Int, Int>>()
    fun crossfadeSettings(block: Block): Pair<Int, Int> = queueStates[block]?.let { it.crossfadeSeconds to it.lastCrossfadeSeconds }
        ?: crossfadeDefaults[block] ?: (3 to 3)
    fun restoreCrossfade(block: Block, seconds: Int, last: Int) { crossfadeDefaults[block] = seconds.coerceIn(0, 30) to last.coerceIn(1, 30) }
    fun clearSettings(block: Block) { crossfadeDefaults.remove(block) }
    private val queueStates = ConcurrentHashMap<Block, QueueState>()

    fun queueView(block: Block): QueueView? = queueStates[block]?.let { QueueView(it.items, it.currentIndex, it.crossfadeSeconds) }


    fun changeCrossfade(block: Block, delta: Int) {
        queueStates[block]?.let { state ->
            synchronized(state) {
                state.crossfadeSeconds = (state.crossfadeSeconds + delta).coerceIn(0, 30)
                if (state.crossfadeSeconds > 0) state.lastCrossfadeSeconds = state.crossfadeSeconds
                crossfadeDefaults[block] = state.crossfadeSeconds to state.lastCrossfadeSeconds
            }
        }
    }

    fun toggleCrossfade(block: Block) {
        queueStates[block]?.let { state ->
            synchronized(state) {
                state.crossfadeSeconds = if (state.crossfadeSeconds > 0) 0 else state.lastCrossfadeSeconds
                crossfadeDefaults[block] = state.crossfadeSeconds to state.lastCrossfadeSeconds
            }
        }
    }

    fun isQueued(block: Block) = queues.containsKey(block)
    fun stopQueue(block: Block) { queues.remove(block)?.cancel(); skipping.remove(block); jumpTargets.remove(block) }
    fun jumpToTrack(block: Block, index: Int) {
        val state = queueStates[block] ?: return
        if (index !in state.items.indices || !queues.containsKey(block) || !jukeboxes.isPlaying(block)) return
        jumpTargets[block] = index
        skipping.add(block)
        jukeboxes.stopPlayback(block)
    }

    fun skipTrack(block: Block) {
        if (queues.containsKey(block) && jukeboxes.isPlaying(block)) {
            skipping.add(block)
            jukeboxes.stopPlayback(block)
        }
    }

    override val name = "playlist"
    override val permissions = listOf("playlist" to PermissionDefault.OP)

    private fun Player.allowed(permission: String) =
        hasPermission("pv.addon.discs.$permission") || hasPermission("vc.addon.discs.$permission")

    private fun message(player: Player, text: String, error: Boolean = false) =
        player.sendMessage(Component.text(text, if (error) NamedTextColor.RED else NamedTextColor.GREEN))

    override fun checkCanExecute(sender: CommandSender) =
        (sender.hasPermission("pv.addon.discs.playlist") || sender.hasPermission("vc.addon.discs.playlist")) &&
            (sender.hasPermission("pv.addon.discs.burn") || sender.hasPermission("vc.addon.discs.burn"))

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {
        if (!checkCanExecute(source)) return emptyList()
        return when (arguments.size) {
            2 -> listOf("<playlisturl>")
            3 -> listOf("shuffle", "reverse").filter { it.startsWith(arguments[2], ignoreCase = true) }
            else -> emptyList()
        }
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: run { sender.sendMessage("Player only command"); return }
        if (!checkCanExecute(player)) { message(player, "No permission.", true); return }
        val url = arguments.getOrNull(1)
        val order = arguments.getOrNull(2)?.lowercase(java.util.Locale.ROOT)
        if (url == null || arguments.size !in 2..3 || (order != null && order !in listOf("shuffle", "reverse"))) {
            message(player, "Usage: /disc playlist <playlisturl> [shuffle|reverse]", true)
            return
        }
        val request = Pending()
        pending[player.uniqueId] = request
        message(player, "Loading playlist...")
        scope.launch {
            try {
                val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }
                if (voicePlayer != null && !suspendSyncFor(player, voiceServer, plugin) {
                    PrePlayerBurnEvent(voicePlayer, url).callEvent()
                }) {
                    pending.remove(player.uniqueId, request)
                    return@launch
                }
                val loadedTracks = audioPlayerManager.getPlaylist(url).await().tracks
                val tracks = when (order) {
                    "shuffle" -> loadedTracks.shuffled()
                    "reverse" -> loadedTracks.reversed()
                    else -> loadedTracks.toList()
                }
                require(tracks.isNotEmpty()) { "Playlist has no tracks." }
                request.created = System.currentTimeMillis()
                request.tracks = tracks
                suspendSyncFor(player, voiceServer, plugin) {
                    if (pending[player.uniqueId] === request) {
                        message(player, "Loaded ${tracks.size} tracks (${order ?: "playlist order"}). Right-click a chest above a jukebox within 2 minutes to burn blank discs and play. Sneak-click opens the chest.")
                    }
                }
            } catch (e: CancellationException) {
                pending.remove(player.uniqueId, request)
                throw e
            } catch (e: Exception) {
                if (pending.remove(player.uniqueId, request)) suspendSyncFor(player, voiceServer, plugin) {
                    message(player, "Failed to load playlist: ${e.message ?: "Unknown error"}", true)
                }
            }
        }
    }

    // Respect protection plugins' block-use decisions, including events where item use alone is denied.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onChestClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND ||
            event.useInteractedBlock() == Event.Result.DENY || event.player.isSneaking) return
        val chest = event.clickedBlock?.state as? Chest ?: return
        var block = chest.block.getRelative(BlockFace.DOWN)
        if (block.type != Material.JUKEBOX) {
            val doubleChest = chest.inventory.holder as? DoubleChest
            val leftJukebox = (doubleChest?.leftSide as? Chest)?.block?.getRelative(BlockFace.DOWN)
            val rightJukebox = (doubleChest?.rightSide as? Chest)?.block?.getRelative(BlockFace.DOWN)
            block = when {
                leftJukebox?.type == Material.JUKEBOX -> leftJukebox
                rightJukebox?.type == Material.JUKEBOX -> rightJukebox
                else -> return
            }
        }
        val player = event.player
        val request = pending[player.uniqueId]
        if (request != null && System.currentTimeMillis() - request.created > 120_000) {
            pending.remove(player.uniqueId, request)
            message(player, "Playlist selection expired. Run /disc playlist again.", true)
            return
        }
        if (!player.allowed("play")) return
        if (queues.containsKey(block) || jukeboxes.isPlaying(block) || block.asJukebox()?.record?.type != Material.AIR) {
            message(player, "This jukebox is busy. Right-click the jukebox for playback controls.", true)
            return
        }
        if (request != null) {
            if (!checkCanExecute(player)) { message(player, "No permission to burn playlists.", true); return }
            val tracks = request.tracks ?: run { message(player, "Playlist is still loading.", true); return }
            var burned = 0
            val inventory = chest.inventory
            val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }
            for (slot in 0 until inventory.size) {
                if (burned >= tracks.size) break
                val item = inventory.getItem(slot)?.clone() ?: continue
                if (!discHelper.isRecord(item) || item.amount != 1) continue
                if (config.burnableTag.requireBurnableTag &&
                    !item.itemMeta.persistentDataContainer.has(AddonKeys.burnableKey, PersistentDataType.BYTE) &&
                    !player.allowed("burn.burnable_check_bypass")) continue
                val track = tracks[burned]
                val original = item.clone()
                if (voicePlayer != null && !PlayerBurnEvent(voicePlayer, track, item).callEvent()) continue
                if (inventory.getItem(slot) != original) continue
                val identifier = track.info.uri?.takeIf { it.isNotBlank() } ?: track.identifier
                if (Bukkit.getServer().getMinecraftVersionInt() >= 12103) discHelper.showSongTooltip(item, false)
                val label = formatTrackDisplay(track.info.title, track.info.author)
                item.burnMetadata(identifier, label, config, discHelper)
                inventory.setItem(slot, item)
                burned++
            }
            pending.remove(player.uniqueId, request)
            if (burned > 0) {
                message(player, "Burned $burned of ${tracks.size} playlist tracks in chest slot order.")
            } else {
                message(player, "No eligible music discs found.", true)
            }
        }
        val discs = chest.inventory.contents.filterNotNull()
            .filter { it.isCustomDisc(discHelper) }.map { it.clone() }
        if (discs.isEmpty()) {
            if (request != null) message(player, "No playable discs found in the chest.", true)
            return
        }
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        fun queueItems(discs: List<ItemStack>) = discs.map { disc ->
            QueueItem(disc.itemMeta.lore()?.firstOrNull()?.let { PlainTextComponentSerializer.plainText().serialize(it) }
                ?.takeIf { it.isNotBlank() } ?: disc.customDiscIdentifier(discHelper)!!,
                "item/${disc.type.name.lowercase(java.util.Locale.ROOT)}")
        }
        val state = QueueState(queueItems(discs))
        crossfadeSettings(block).let { (seconds, last) -> state.crossfadeSeconds = seconds; state.lastCrossfadeSeconds = last }
        val queue = scope.launch(start = CoroutineStart.LAZY) {
            var current: Job? = null
            var outgoing: Job? = null
            var controls = PlaybackControls()
            var oldControls: PlaybackControls? = null
            var liveDiscs = discs
            var index = 0
            var activeDisc = discs.first()
            var removedNextIndex = 0
            var refreshTicks = 0
            var transitionMs = 0L
            suspend fun intact() = suspendSyncAt(block.location, voiceServer, plugin) {
                block.type == Material.JUKEBOX && chest.block.state is Chest &&
                    block.asJukebox()?.record?.type == Material.AIR
            }
            suspend fun start(index: Int, nextControls: PlaybackControls, overlap: Boolean): Job? =
                suspendSyncAt(block.location, voiceServer, plugin) {
                    if (chest.block.state !is Chest || block.type != Material.JUKEBOX ||
                        block.asJukebox()?.record?.type != Material.AIR || (!overlap && jukeboxes.isPlaying(block))) null
                    else {
                        state.currentIndex = index
                        val disc = liveDiscs.getOrNull(index) ?: return@suspendSyncAt null
                        activeDisc = disc
                        val occurrence = liveDiscs.take(index).count { it == disc }
                        jukeboxes.playChestTrack(disc.customDiscIdentifier(discHelper)!!, block, disc.itemMeta,
                            player, controls = nextControls, overlap = overlap, onFailure = {
                                val inventory = (chest.block.state as? Chest)?.inventory
                                if (inventory != null) {
                                    val matchingSlots = (0 until inventory.size).filter { inventory.getItem(it) == disc }
                                    val slot = matchingSlots.getOrNull(occurrence) ?: matchingSlots.firstOrNull()
                                    if (slot != null) {
                                        val blank = inventory.getItem(slot)!!.clone()
                                        blank.eraseBurnMetadata(config, discHelper)
                                        inventory.setItem(slot, blank)
                                    }
                                }
                            })
                    }
                }
            try {
                current = start(index, controls, false) ?: return@launch
                while (isActive) {
                    delay(50)
                    if (!intact()) break
                    if (++refreshTicks >= 5 || current?.isActive != true) {
                        refreshTicks = 0
                        val updated = suspendSyncAt(block.location, voiceServer, plugin) {
                            (chest.block.state as? Chest)?.inventory?.contents?.filterNotNull()
                                ?.filter { it.isCustomDisc(discHelper) }?.map { it.clone() } ?: emptyList()
                        }
                        if (updated != liveDiscs) {
                            val oldIndex = index
                            index = remapQueueIndex(liveDiscs, updated, oldIndex, activeDisc)
                            if (index < 0 && oldIndex >= 0) removedNextIndex = oldIndex.coerceAtMost(updated.size)
                            jumpTargets.computeIfPresent(block) { _, target ->
                                liveDiscs.getOrNull(target)?.let { disc ->
                                    remapQueueIndex(liveDiscs, updated, target, disc).takeIf { it >= 0 }
                                }
                            }
                            liveDiscs = updated
                            state.items = queueItems(updated)
                            state.currentIndex = index
                        }
                    }
                    val playing = current ?: break
                    // Fade progress follows audio time, so pausing freezes both sides of the transition.
                    if (outgoing != null) {
                        val progress = if (controls.isStream || transitionMs <= 0) 1.0 else controls.position.toDouble() / transitionMs
                        controls.setGain(progress)
                        oldControls?.setGain(1.0 - progress)
                        if (progress >= 1.0 || outgoing?.isActive != true || !playing.isActive) {
                            outgoing?.cancelAndJoin()
                            outgoing = null
                            oldControls = null
                            controls.transitionPartner = null
                            controls.setGain(1.0)
                        }
                    }
                    if (!playing.isActive) {
                        playing.join()
                        if (playing.isCancelled && !skipping.remove(block)) break
                        index = jumpTargets.remove(block) ?: (if (index >= 0) index + 1 else removedNextIndex)
                        if (index >= liveDiscs.size) break
                        controls = PlaybackControls(initialVolume = controls.volume)
                        current = start(index, controls, false) ?: break
                        continue
                    }
                    val fadeMs = state.crossfadeSeconds * 1000L
                    val remaining = controls.duration - controls.position
                    val nextIndex = if (index >= 0) index + 1 else removedNextIndex
                    if (outgoing == null && nextIndex < liveDiscs.size && fadeMs > 0 &&
                        controls.seekable && !controls.isStream && !controls.paused &&
                        controls.duration > 0 && remaining in 1..fadeMs) {
                        val nextControls = PlaybackControls(initialVolume = controls.volume, initialGain = 0.0)
                        val next = start(nextIndex, nextControls, true) ?: break
                        transitionMs = remaining.coerceAtLeast(1)
                        outgoing = playing
                        oldControls = controls
                        nextControls.transitionPartner = controls
                        controls = nextControls
                        current = next
                        index = nextIndex
                    }
                }
            } finally {
                controls.transitionPartner = null
                withContext(NonCancellable) {
                    current?.cancelAndJoin()
                    outgoing?.cancelAndJoin()
                }
            }
        }
        queueStates[block] = state
        queues[block] = queue
        queue.invokeOnCompletion {
            queueStates.remove(block, state)
            if (queues.remove(block, queue)) { skipping.remove(block); jumpTargets.remove(block) }
        }
        queue.start()
        message(player, "Playing ${discs.size} discs in chest slot order. Sneak-click the chest to open it.")
    }

    private fun stop(block: Block) {
        queues.remove(block)?.cancel()
        queues.remove(block.getRelative(BlockFace.DOWN))?.cancel()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = stop(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) = event.blockList().forEach(::stop)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) = event.blockList().forEach(::stop)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        queues.keys.filter { it.inChunk(event.chunk) }.forEach { queues.remove(it)?.cancel() }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
}
