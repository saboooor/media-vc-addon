package su.plo.voice.discs.command.subcommand

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
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
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.inject
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.audio.AutoMix
import su.plo.voice.discs.audio.MixFeatures
import su.plo.voice.discs.audio.MixPlan
import su.plo.voice.discs.audio.MixEnvelope
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
    init { jukeboxes.shouldLoop = { playbackModes(it).first } }
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
    private val autoMixSettings = ConcurrentHashMap<Block, Boolean>()
    fun autoMixEnabled(block: Block) = autoMixSettings[block] ?: false
    fun restoreAutoMix(block: Block, enabled: Boolean) { autoMixSettings[block] = enabled }
    fun toggleAutoMix(block: Block) { restoreAutoMix(block, !autoMixEnabled(block)) }
    private data class PreparedMix(val disc: ItemStack, val track: AudioTrack, val features: MixFeatures?)
    private val modes = ConcurrentHashMap<Block, Pair<Boolean, Boolean>>()
    fun playbackModes(block: Block) = modes[block] ?: (false to false)
    fun restoreModes(block: Block, loop: Boolean, shuffle: Boolean) { modes[block] = loop to shuffle }
    fun toggleLoop(block: Block) {
        val mode = playbackModes(block)
        val newLoop = !mode.first
        restoreModes(block, newLoop, mode.second)
        if (!newLoop) {
            pendingLoopQueues.remove(block)
            jukeboxes.cancelPendingLoopResume(block)
        }
    }
    fun toggleShuffle(block: Block) { val mode = playbackModes(block); restoreModes(block, mode.first, !mode.second) }
    fun clearSettings(block: Block) { crossfadeDefaults.remove(block); autoMixSettings.remove(block); modes.remove(block); pendingLoopQueues.remove(block) }
    private val queueStates = ConcurrentHashMap<Block, QueueState>()
    private val pendingLoopQueues = ConcurrentHashMap<Block, Int>()

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
    fun stopQueue(block: Block) {
        pendingLoopQueues.remove(block)
        pendingLoopQueues.remove(block.getRelative(BlockFace.DOWN))
        queues.remove(block)?.cancel()
        skipping.remove(block)
        jumpTargets.remove(block)
    }
    fun jumpToTrack(block: Block, index: Int) {
        val state = queueStates[block] ?: return
        if (index !in state.items.indices || !queues.containsKey(block) || !jukeboxes.isPlaying(block)) return
        jumpTargets[block] = index
        skipping.add(block)
    }

    fun skipTrack(block: Block) {
        if (queues.containsKey(block) && jukeboxes.isPlaying(block)) {
            skipping.add(block)
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
        if (!player.allowed("play") || request == null) return
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
        startChestQueue(block, chest, player)
    }

    /** Called only on the inventory's server thread, after the inventory mutation commits. */
    fun startChestQueue(block: Block, chest: Chest, player: Player? = null, initialIndex: Int? = null) {
        if (block.type != Material.JUKEBOX || isQueued(block) || jukeboxes.isPlaying(block) ||
            block.asJukebox()?.record?.type != Material.AIR) return
        val discs = chest.inventory.contents.filterNotNull()
            .filter { it.isCustomDisc(discHelper) }.map { it.clone() }
        if (discs.isEmpty()) {
            return
        }
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
            var controls = PlaybackControls(initialFilterConfig = jukeboxes.filterConfig(block))
            var oldControls: PlaybackControls? = null
            var liveDiscs = discs
            val order = ChestQueueOrder()
            var index = (initialIndex ?: jumpTargets.remove(block))?.takeIf { it in discs.indices }
                ?: order.next(discs.size, -1, false, playbackModes(block).second) ?: return@launch
            var activeDisc = discs.first()
            var removedNextIndex = 0
            var refreshTicks = 0
            var envelope: MixEnvelope? = null
            var outgoingDisc: ItemStack? = null
            var outgoingIndex = 0
            var recoveryStart = 0L
            var recoveryGain = 1.0
            var recoveryTempo = 1.0
            var haltAtEnd = false
            var fadeOutStart: Long? = null
            var fadeOutGain = 1.0
            var fadeOutMs = 0L
            var preparing: Deferred<PreparedMix?>? = null
            var preparingDisc: ItemStack? = null
            fun fadeDuration() = if (autoMixEnabled(block)) 2_000L else state.crossfadeSeconds * 1_000L
            fun prepare(target: Int) {
                val disc = liveDiscs.getOrNull(target) ?: return
                if (preparing != null && preparingDisc == disc) return
                preparing?.cancel()
                preparingDisc = disc.clone()
                preparing = async(Dispatchers.IO) {
                    try {
                        withTimeout(15_000L) {
                            val track = audioPlayerManager.getTrack(disc.customDiscIdentifier(discHelper)!!).await()
                            PreparedMix(disc, track, if (autoMixEnabled(block)) audioPlayerManager.analyzeMixIntro(track) else null)
                        }
                    } catch (e: CancellationException) {
                        if (e !is TimeoutCancellationException) throw e
                        null
                    } catch (_: Exception) { null } // Playback reports failures; speculative loading does not erase discs.
                }
            }
            suspend fun prepared(target: Int): PreparedMix? =
                if (preparingDisc == liveDiscs.getOrNull(target) && preparing?.isCompleted == true) preparing?.await() else null
            suspend fun intact() = suspendSyncAt(block.location, voiceServer, plugin) {
                block.type == Material.JUKEBOX && chest.block.state is Chest &&
                    block.asJukebox()?.record?.type == Material.AIR
            }
            suspend fun start(index: Int, nextControls: PlaybackControls, overlap: Boolean, prepared: PreparedMix? = null): Job? =
                suspendSyncAt(block.location, voiceServer, plugin) {
                    if (chest.block.state !is Chest || block.type != Material.JUKEBOX ||
                        block.asJukebox()?.record?.type != Material.AIR || (!overlap && jukeboxes.isPlaying(block))) null
                    else {
                        state.currentIndex = index
                        val disc = liveDiscs.getOrNull(index) ?: return@suspendSyncAt null
                        activeDisc = disc
                        val occurrence = liveDiscs.take(index).count { it == disc }
                        jukeboxes.playChestTrack(disc.customDiscIdentifier(discHelper)!!, block, disc.itemMeta,
                            player, controls = nextControls, overlap = overlap, preparedTrack = prepared?.track, onFailure = {
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
            suspend fun recoverOutgoing(): Boolean {
                val old = outgoing ?: return false
                val oldState = oldControls ?: return false
                if (!old.isActive) return false
                current?.cancelAndJoin()
                controls.transitionPartner = null
                current = old; controls = oldState
                activeDisc = outgoingDisc ?: activeDisc
                index = remapQueueIndex(liveDiscs, liveDiscs, outgoingIndex, activeDisc)
                state.currentIndex = index
                outgoing = null; oldControls = null; outgoingDisc = null; envelope = null
                recoveryGain = controls.currentGain; recoveryTempo = controls.mixTempoFactor; recoveryStart = controls.sentAudioMs
                suspendSyncAt(block.location, voiceServer, plugin) { jukeboxes.restorePlayback(block, old, controls) }
                return true
            }
            suspend fun transition(target: Int, plan: MixPlan?, manual: Boolean): Boolean {
                // Repeated skips while loading retain the song that is actually audible.
                if (controls.sentAudioMs == 0L && outgoing != null) recoverOutgoing()
                controls.transitionPartner = null
                outgoing?.cancelAndJoin(); outgoing = null; oldControls = null
                val ready = prepared(target)
                val mixPlan = plan ?: if (autoMixEnabled(block) && ready?.features != null)
                    AutoMix.plan(controls.duration, ready.track.duration, controls.mixAnalysis.snapshot(), ready.features,
                        controls.playbackRate, controls.filterConfig.speed * controls.filterConfig.tempo) else null
                val fade = if (manual) fadeDuration() else plan?.durationMs ?: fadeDuration()
                if (fade <= 0) {
                    current?.cancelAndJoin()
                    controls = PlaybackControls(initialVolume = controls.volume, initialFilterConfig = controls.filterConfig)
                    controls.autoMixAnalysis = autoMixEnabled(block)
                    current = start(target, controls, false, ready) ?: return false
                } else {
                    val previous = current
                    val previousControls = controls
                    val previousDisc = activeDisc
                    val previousIndex = index
                    val cue = if (ready?.track?.isSeekable == true) mixPlan?.cueMs ?: 0L else 0L
                    val nextControls = PlaybackControls(initialVolume = controls.volume, initialGain = 0.0,
                        initialPositionMs = cue, initialFilterConfig = controls.filterConfig)
                    nextControls.autoMixAnalysis = autoMixEnabled(block)
                    nextControls.setMixTempoFactor(mixPlan?.tempoFactor ?: 1.0)
                    if (controls.paused) nextControls.togglePause()
                    nextControls.transitionPartner = previousControls
                    val next = start(target, nextControls, true, ready) ?: return false
                    envelope = MixEnvelope(fade, previousControls.currentGain, plan?.incomingGain ?: 1.0)
                    outgoing = previous; oldControls = previousControls
                    outgoingDisc = previousDisc; outgoingIndex = previousIndex
                    controls = nextControls; current = next
                }
                preparing?.cancel(); preparing = null; preparingDisc = null
                index = target; fadeOutStart = null; haltAtEnd = false; recoveryGain = 1.0; recoveryTempo = 1.0
                return true
            }
            try {
                controls.autoMixAnalysis = autoMixEnabled(block)
                current = start(index, controls, false) ?: return@launch
                while (isActive) {
                    delay(50)
                    if (!intact()) break
                    controls.autoMixAnalysis = autoMixEnabled(block)
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
                            outgoingDisc?.let { outgoingIndex = remapQueueIndex(liveDiscs, updated, outgoingIndex, it) }
                            order.reset()
                            preparing?.cancel(); preparing = null; preparingDisc = null
                            liveDiscs = updated
                            state.items = queueItems(updated)
                            state.currentIndex = index
                        }
                    }
                    var playing = current ?: break
                    if (skipping.remove(block)) {
                        val target = jumpTargets.remove(block) ?: order.next(liveDiscs.size, index,
                            playbackModes(block).first, playbackModes(block).second, removedNextIndex)
                        if (target == null) {
                            if (controls.sentAudioMs == 0L && outgoing != null) recoverOutgoing()
                            playing = current ?: break
                            if (fadeDuration() <= 0) break
                            controls.transitionPartner = null
                            outgoing?.cancelAndJoin(); outgoing = null; oldControls = null; envelope = null
                            if (fadeOutStart == null) {
                                fadeOutStart = controls.sentAudioMs; fadeOutGain = controls.currentGain; fadeOutMs = fadeDuration()
                            }
                        } else {
                            if (!transition(target, null, true)) break
                            continue
                        }
                    }
                    fadeOutStart?.let { began ->
                        val progress = (controls.sentAudioMs - began).toDouble() / fadeOutMs.coerceAtLeast(1)
                        controls.setGain(AutoMix.gains(progress, fadeOutGain).first)
                    }
                    if (fadeOutStart != null) {
                        if (!playing.isActive || controls.sentAudioMs - fadeOutStart!! >= fadeOutMs) break
                        continue
                    }
                    if (outgoing != null) {
                        if (controls.failure != null || !playing.isActive && controls.sentAudioMs == 0L) {
                            if (recoverOutgoing()) { haltAtEnd = true; continue }
                        }
                        val fade = envelope!!
                        if (controls.sentAudioMs > 0L) {
                            val gains = fade.gains(controls.sentAudioMs)
                            oldControls?.setGain(gains.first); controls.setGain(gains.second)
                        }
                        // No fade clock advances until incoming audio is transmitted, including live streams.
                        if (fade.complete(controls.sentAudioMs) || outgoing?.isActive != true) {
                            outgoing?.cancelAndJoin(); outgoing = null; oldControls = null; outgoingDisc = null
                            controls.transitionPartner = null
                            recoveryGain = controls.currentGain
                            recoveryTempo = controls.mixTempoFactor
                            recoveryStart = controls.sentAudioMs
                            if (controls.sentAudioMs == 0L) { controls.setGain(1.0); recoveryGain = 1.0 }
                            envelope = null
                        }
                    } else if (recoveryGain < 1.0 || recoveryTempo != 1.0) {
                        val progress = ((controls.sentAudioMs - recoveryStart) / 2_000.0).coerceIn(0.0, 1.0)
                        controls.setGain(recoveryGain + (1.0 - recoveryGain) * progress)
                        if (progress >= 1.0) recoveryGain = 1.0
                        controls.setMixTempoFactor(AutoMix.releaseTempo(recoveryTempo, controls.sentAudioMs - recoveryStart))
                        if (controls.sentAudioMs - recoveryStart >= 4_000) recoveryTempo = 1.0
                    }
                    playing = current ?: break
                    if (!playing.isActive) {
                        playing.join()
                        if (playing.isCancelled || haltAtEnd) break
                        if (controls.failure != null && activeDisc.identifier()?.startsWith("jellyfin:") == true) break
                        val target = order.next(liveDiscs.size, index, playbackModes(block).first,
                            playbackModes(block).second, removedNextIndex) ?: break
                        if (!transition(target, null, true)) break
                        continue
                    }
                    if (outgoing != null || haltAtEnd || controls.paused) continue
                    val nextIndex = order.peek(liveDiscs.size, index, playbackModes(block).first,
                        playbackModes(block).second, removedNextIndex) ?: continue
                    val remaining = controls.duration - controls.position
                    if (fadeDuration() > 0 && controls.duration > 0 && remaining in 1..45_000) prepare(nextIndex)
                    val ready = prepared(nextIndex)
                    val plan = if (autoMixEnabled(block)) AutoMix.plan(controls.duration,
                        ready?.track?.duration ?: 0L, controls.mixAnalysis.snapshot(), ready?.features, controls.playbackRate,
                        controls.filterConfig.speed * controls.filterConfig.tempo)
                        else MixPlan((controls.duration - fadeDuration()).coerceAtLeast(0), fadeDuration())
                    if (plan.durationMs > 0 && controls.seekable && !controls.isStream && controls.duration > 0 &&
                        controls.position >= plan.startMs && remaining > 0) {
                        val target = order.next(liveDiscs.size, index, playbackModes(block).first,
                            playbackModes(block).second, removedNextIndex) ?: continue
                        if (!transition(target, plan, false)) break
                    }
                }
            } finally {
                controls.transitionPartner = null
                withContext(NonCancellable) {
                    preparing?.cancelAndJoin()
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

    }

    private fun scheduleChest(chest: Chest, player: Player? = null) {
        scope.launch {
            delay(100)
            suspendSyncAt(chest.location, voiceServer, plugin) {
                val live = chest.block.state as? Chest ?: return@suspendSyncAt
                val halves = (live.inventory.holder as? DoubleChest)?.let {
                    listOfNotNull(it.leftSide as? Chest, it.rightSide as? Chest)
                } ?: listOf(live)
                halves.firstOrNull { it.block.getRelative(BlockFace.DOWN).type == Material.JUKEBOX }?.let {
                    startChestQueue(it.block.getRelative(BlockFace.DOWN), live, player)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChestEdit(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!player.allowed("play")) return
        chestHolder(event.view.topInventory.holder)?.let { scheduleChest(it, player) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChestDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!player.allowed("play")) return
        chestHolder(event.view.topInventory.holder)?.let { scheduleChest(it, player) }
    }

    private fun chestHolder(holder: org.bukkit.inventory.InventoryHolder?): Chest? = when (holder) {
        is Chest -> holder
        is DoubleChest -> holder.leftSide as? Chest
        else -> null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChestClose(event: InventoryCloseEvent) {
        val chest = when (val holder = event.inventory.holder) {
            is Chest -> holder
            is DoubleChest -> holder.leftSide as? Chest
            else -> null
        } ?: return
        val player = event.player as? Player ?: return
        if (player.allowed("play")) scheduleChest(chest, player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChestTransfer(event: InventoryMoveItemEvent) {
        val holder = event.destination.holder
        val chest = holder as? Chest ?: (holder as? DoubleChest)?.leftSide as? Chest ?: return
        scheduleChest(chest)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChestPlace(event: BlockPlaceEvent) {
        val chest = event.blockPlaced.state as? Chest ?: return
        if (!event.player.allowed("play")) return
        scope.launch {
            delay(100)
            suspendSyncAt(chest.location, voiceServer, plugin) {
                val live = chest.block.state as? Chest ?: return@suspendSyncAt
                val block = live.block.getRelative(BlockFace.DOWN)
                val jukebox = block.asJukebox() ?: return@suspendSyncAt
                val record = jukebox.record
                if (record.type != Material.AIR && live.inventory.addItem(record.clone()).isEmpty()) {
                    jukeboxes.stopPlayback(block)
                    jukebox.setRecord(null)
                    jukebox.update()
                }
                scheduleChest(live, event.player)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDiscToChest(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND ||
            event.player.isSneaking || event.useInteractedBlock() == Event.Result.DENY ||
            event.useItemInHand() == Event.Result.DENY || !event.player.allowed("play")) return
        val block = event.clickedBlock?.takeIf { it.type == Material.JUKEBOX } ?: return
        val chest = block.getRelative(BlockFace.UP).state as? Chest ?: return
        val item = event.item?.takeIf { discHelper.isRecord(it) } ?: return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        val disc = item.clone().apply { amount = 1 }
        if (chest.inventory.addItem(disc).isNotEmpty()) {
            message(event.player, "The chest is full.", true)
            return
        }
        // Transfer, including creative mode: the chest receives exactly one real item.
        event.player.inventory.setItemInMainHand(item.clone().apply { amount -= 1 })
        scheduleChest(chest, event.player)
    }

    private fun stop(block: Block) {
        pendingLoopQueues.remove(block)
        pendingLoopQueues.remove(block.getRelative(BlockFace.DOWN))
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
        queues.keys.filter { it.inChunk(event.chunk) }.forEach { block ->
            if (playbackModes(block).first) {
                pendingLoopQueues[block] = queueStates[block]?.currentIndex ?: 0
            }
            queues.remove(block)?.cancel()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLoad(event: ChunkLoadEvent) {
        val pendingInChunk = pendingLoopQueues.keys.filter { it.inChunk(event.chunk) }
        if (pendingInChunk.isEmpty()) return
        scope.launch {
            delay(100)
            suspendSyncAt(Location(event.world, (event.chunk.x shl 4) + 8.0, 64.0, (event.chunk.z shl 4) + 8.0), voiceServer, plugin) {
                pendingInChunk.forEach { block ->
                    val resumeIndex = pendingLoopQueues.remove(block) ?: return@forEach
                    if (!playbackModes(block).first || isQueued(block) || jukeboxes.isPlaying(block)) return@forEach
                    val chest = block.getRelative(BlockFace.UP).state as? Chest ?: return@forEach
                    if (block.type == Material.JUKEBOX && block.asJukebox()?.record?.type == Material.AIR) {
                        startChestQueue(block, chest, initialIndex = resumeIndex)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
}


