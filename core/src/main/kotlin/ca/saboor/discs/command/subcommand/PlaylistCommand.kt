package ca.saboor.discs.command.subcommand

import ca.saboor.discs.PlaybackControls
import ca.saboor.discs.audio.AutoMix
import ca.saboor.discs.audio.MixEnvelope
import ca.saboor.discs.audio.MixFeatures
import ca.saboor.discs.audio.MixPlan
import ca.saboor.discs.audio.PlaybackFailure
import ca.saboor.discs.utils.extend.burnMetadata
import ca.saboor.discs.utils.extend.eraseBurnMetadata
import ca.saboor.discs.utils.extend.formatTrackDisplay
import ca.saboor.discs.utils.extend.suspendSyncAt
import ca.saboor.discs.utils.extend.suspendSyncFor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.inject
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.api.PlayerBurnEvent
import su.plo.voice.discs.api.PrePlayerBurnEvent
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.item.DiscHelper
import su.plo.voice.discs.utils.extend.*
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack

/** Chest inventories are only inspected/edited on their region's server thread. */
class PlaylistCommand(private val jukeboxes: JukeboxEventListener) : SubCommand(), Listener {
    private val discHelper: DiscHelper by inject()
    init { jukeboxes.shouldLoop = { playbackModes(it).first } }
    private class Pending(val overwrite: Boolean = false, @Volatile var created: Long = System.currentTimeMillis()) {
        @Volatile var tracks: List<AudioTrack>? = null
        @Volatile var songs: List<ca.saboor.discs.playlist.CatalogPlaylistClient.Song>? = null
        val matching = java.util.concurrent.atomic.AtomicBoolean(false)
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
    private data class PreparedMix(val disc: ItemStack, val track: AudioTrack, val sections: List<MixFeatures>)
    private data class MixCommitKey(val controls: PlaybackControls, val nextIdentifier: String, val occurrence: Int,
                                    val baseRate: Double)

    private val modes = ConcurrentHashMap<Block, Pair<Boolean, Boolean>>()
    fun playbackModes(block: Block) = modes[block] ?: (false to false)
    fun restoreModes(block: Block, loop: Boolean, shuffle: Boolean) { modes[block] = loop to shuffle }
    fun toggleLoop(block: Block) {
        val mode = playbackModes(block)
        val newLoop = !mode.first
        restoreModes(block, newLoop, mode.second)
    }
    fun toggleShuffle(block: Block) { val mode = playbackModes(block); restoreModes(block, mode.first, !mode.second) }
    fun clearSettings(block: Block) { crossfadeDefaults.remove(block); autoMixSettings.remove(block); modes.remove(block); pendingLoopQueues.remove(block) }
    private val queueStates = ConcurrentHashMap<Block, QueueState>()
    private data class QueueResume(val index: Int, val disc: ItemStack, val positionMs: Long, val paused: Boolean)
    private val pendingLoopQueues = ConcurrentHashMap<Block, QueueResume>()

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

    private val restarting = ConcurrentHashMap.newKeySet<Block>()
    fun restartTrack(block: Block): Boolean = isQueued(block) && restarting.add(block)

    fun isQueued(block: Block) = queues.containsKey(block)
    fun stopQueue(block: Block) {
        restarting.remove(block)
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
            3, 4 -> listOf("shuffle", "reverse", "overwrite").filter { option ->
                option !in arguments.drop(2).dropLast(1).map { it.lowercase(java.util.Locale.ROOT) } &&
                    !(option in listOf("shuffle", "reverse") && arguments.drop(2).dropLast(1).any { it.equals("shuffle", true) || it.equals("reverse", true) }) &&
                    option.startsWith(arguments.last(), ignoreCase = true)
            }
            else -> emptyList()
        }
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: run { sender.sendMessage("Player only command"); return }
        if (!checkCanExecute(player)) { message(player, "No permission.", true); return }
        val url = arguments.getOrNull(1)
        val options = arguments.drop(2).map { it.lowercase(java.util.Locale.ROOT) }
        val order = options.firstOrNull { it == "shuffle" || it == "reverse" }
        if (url == null || arguments.size !in 2..4 || options.any { it !in listOf("shuffle", "reverse", "overwrite") } ||
            options.distinct().size != options.size || options.count { it == "shuffle" || it == "reverse" } > 1) {
            message(player, "Usage: /disc playlist <playlisturl> [shuffle|reverse] [overwrite]", true)
            return
        }
        val request = Pending(overwrite = "overwrite" in options)
        pending[player.uniqueId] = request
        message(player, if (request.overwrite) "Loading playlist (overwrite enabled: existing disc songs can be replaced)..." else "Loading playlist (existing disc songs will be preserved)...")
        scope.launch {
            try {
                val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }
                if (voicePlayer != null && !suspendSyncFor(player, voiceServer, plugin) {
                    PrePlayerBurnEvent(voicePlayer, url).callEvent()
                }) {
                    pending.remove(player.uniqueId, request)
                    return@launch
                }
                val catalog = ca.saboor.discs.playlist.CatalogPlaylistClient.parse(url)
                val loadedTracks = if (catalog == null) audioPlayerManager.getPlaylist(url).await().tracks else {
                    val settings = config.playlistSources
                    val token = if (catalog.service == "Spotify")
                        System.getenv("SPOTIFY_ACCESS_TOKEN")?.takeIf { it.isNotBlank() } ?: settings.spotifyAccessToken
                    else System.getenv("APPLE_MUSIC_DEVELOPER_TOKEN")?.takeIf { it.isNotBlank() } ?: settings.appleMusicDeveloperToken
                    val imported = withContext(Dispatchers.IO) {
                        if (token.isBlank()) ca.saboor.discs.playlist.PublicPlaylistClient().load(catalog)
                        else ca.saboor.discs.playlist.PublicPlaylistClient.Result(
                            ca.saboor.discs.playlist.CatalogPlaylistClient().load(catalog, token))
                    }
                    imported.notice?.let { notice -> suspendSyncFor(player, voiceServer, plugin) { message(player, notice) } }
                    request.songs = when (order) {
                        "shuffle" -> imported.songs.shuffled()
                        "reverse" -> imported.songs.reversed()
                        else -> imported.songs
                    }
                    emptyList()
                }
                val tracks = when (order) {
                    "shuffle" -> loadedTracks.shuffled()
                    "reverse" -> loadedTracks.reversed()
                    else -> loadedTracks.toList()
                }
                require(tracks.isNotEmpty() || !request.songs.isNullOrEmpty()) { "Playlist has no tracks." }
                request.created = System.currentTimeMillis()
                request.tracks = tracks
                suspendSyncFor(player, voiceServer, plugin) {
                    if (pending[player.uniqueId] === request) {
                        message(player, "Loaded ${request.songs?.size ?: tracks.size} tracks (${order ?: "playlist order"}). Right-click a chest above a jukebox within 2 minutes to burn blank discs and play. Sneak-click opens the chest.")
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
        if (request != null && !request.matching.get() && System.currentTimeMillis() - request.created > 120_000) {
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
            val songs = request.songs
            if (songs != null) {
                if (!request.matching.compareAndSet(false, true)) {
                    message(player, "Playlist songs are already being matched.")
                    return
                }
                val slots = (0 until chest.inventory.size).mapNotNull { slot ->
                    chest.inventory.getItem(slot)?.takeIf { eligibleDisc(player, it, request.overwrite) }?.clone()?.let { slot to it }
                }.take(songs.size).toMap()
                if (slots.isEmpty()) {
                    request.matching.set(false)
                    message(player, "No eligible blank music discs found.", true)
                    return
                }
                event.isCancelled = true
                message(player, "Matching ${slots.size} songs for ${slots.size} ${if (request.overwrite) "eligible" else "blank"} discs...")
                scope.launch {
                    try {
                        val matched = songs.take(slots.size).mapIndexed { index, song ->
                            ensureActive()
                            if (pending[player.uniqueId] !== request) throw CancellationException("Playlist selection replaced")
                            withTimeout(30_000L) {
                                audioPlayerManager.getPlaylist("ytsearch:${song.query}").await().tracks.firstOrNull()
                                    ?: error("No playable match for song ${index + 1}: ${song.artist} - ${song.title}")
                            }
                        }
                        suspendSyncAt(block.location, voiceServer, plugin) {
                            if (pending[player.uniqueId] !== request) return@suspendSyncAt
                            val liveChest = chest.block.state as? Chest ?: return@suspendSyncAt
                            if (block.type != Material.JUKEBOX || queues.containsKey(block) || jukeboxes.isPlaying(block) ||
                                block.asJukebox()?.record?.type != Material.AIR || !checkCanExecute(player) || !player.allowed("play")) {
                                message(player, "Chest or jukebox changed while matching. Try clicking again.", true)
                                return@suspendSyncAt
                            }
                            burnPlaylist(player, block, liveChest, request, matched, slots)
                        }
                    } catch (e: TimeoutCancellationException) {
                        messageOnPlayerThread(player, "Song matching timed out. Try clicking the chest again.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        messageOnPlayerThread(player, "Failed to match playlist: ${e.message ?: "Unknown error"}")
                    } finally {
                        request.matching.set(false)
                        request.created = System.currentTimeMillis()
                    }
                }
                return
            }
            burnPlaylist(player, block, chest, request, tracks)
        }
    }

    private suspend fun messageOnPlayerThread(player: Player, text: String) {
        suspendSyncFor(player, voiceServer, plugin) { message(player, text, true) }
    }

    private fun eligibleDisc(player: Player, item: ItemStack, overwrite: Boolean): Boolean =
        discHelper.isRecord(item) && item.amount == 1 && (overwrite || !item.isCustomDisc(discHelper)) &&
            (!config.burnableTag.requireBurnableTag ||
                item.itemMeta.persistentDataContainer.has(AddonKeys.burnableKey, PersistentDataType.BYTE) ||
                player.allowed("burn.burnable_check_bypass"))

    private fun burnPlaylist(player: Player, block: Block, chest: Chest, request: Pending,
                             tracks: List<AudioTrack>, slots: Map<Int, ItemStack>? = null) {
        var burned = 0
        val inventory = chest.inventory
        // Matching reserves specific blank discs. If any changed, leave all discs untouched.
        if (slots != null && slots.any { (slot, original) -> inventory.getItem(slot) != original || !eligibleDisc(player, original, request.overwrite) }) {
            message(player, "Chest contents changed while matching. Click again to retry.", true)
            return
        }
        val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }
        for (slot in slots?.keys ?: (0 until inventory.size).toList()) {
            if (burned >= tracks.size) break
            val item = inventory.getItem(slot)?.clone() ?: continue
            if (!eligibleDisc(player, item, request.overwrite)) continue
            val track = tracks[burned]
            val original = item.clone()
            if (voicePlayer != null && !PlayerBurnEvent(voicePlayer, track, item).callEvent()) continue
            if (inventory.getItem(slot) != original) continue
            val identifier = track.info.uri?.takeIf { it.isNotBlank() } ?: track.identifier
            if (Bukkit.getServer().getMinecraftVersionInt() >= 12103) discHelper.showSongTooltip(item, false)
            item.burnMetadata(identifier, formatTrackDisplay(track.info.title, track.info.author), config, discHelper)
            inventory.setItem(slot, item)
            burned++
        }
        pending.remove(player.uniqueId, request)
        if (burned > 0) message(player, "Burned $burned playlist tracks in chest slot order.")
        else message(player, "No eligible blank music discs found.", true)
        startChestQueue(block, chest, player)
    }

    /** Called only on the inventory's server thread, after the inventory mutation commits. */
    fun startChestQueue(block: Block, chest: Chest, player: Player? = null, initialIndex: Int? = null, initialPositionMs: Long = 0L, initiallyPaused: Boolean = false) {
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
            var controls = PlaybackControls(initialFilterConfig = jukeboxes.filterConfig(block), initialPositionMs = initialPositionMs)
            if (initiallyPaused) controls.togglePause()
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
            val mixCommitment = ca.saboor.discs.audio.MixPlanCommitment<MixCommitKey>()
            var preparing: Deferred<PreparedMix?>? = null
            var preparingDisc: ItemStack? = null
            var preparingForAutoMix = false
            var preparationFailed = false
            var preparingTarget = -1
            fun fadeDuration() = if (autoMixEnabled(block)) 2_000L else state.crossfadeSeconds * 1_000L
            fun prepare(target: Int) {
                val disc = liveDiscs.getOrNull(target) ?: return
                val analyze = autoMixEnabled(block)
                if (preparing != null && preparingDisc == disc && preparingForAutoMix == analyze) return
                preparing?.cancel()
                preparingDisc = disc.clone()
                preparingTarget = target
                preparingForAutoMix = analyze
                preparationFailed = false
                preparing = async(Dispatchers.IO) {
                    try {
                        val track = withTimeout(15_000L) {
                            audioPlayerManager.getTrack(disc.customDiscIdentifier(discHelper)!!).await()
                        }
                        audioPlayerManager.prepareSongEdges(track)
                        // No beat estimate is a normal fallback; a decoder/load exception is a failed song.
                        val features = if (analyze) audioPlayerManager.analyzeMixSections(track) else emptyList()
                        PreparedMix(disc, track, features)
                    } catch (e: CancellationException) {
                        if (e !is TimeoutCancellationException) throw e
                        preparationFailed = true
                        null
                    } catch (e: Exception) {
                        // Retry through normal playback for a useful error, without erasing a valid URL.
                        preparationFailed = !PlaybackFailure.keepDisc(e)
                        audioPlayerManager.reportPlaybackFailure(disc.customDiscIdentifier(discHelper)!!,
                            PlaybackFailure.message(e, "Next-song preload failed"), e, "Next-song preload")
                        null
                    }
                }
            }
            suspend fun prepared(target: Int): PreparedMix? =
                if (preparingDisc == liveDiscs.getOrNull(target) && preparing?.isCompleted == true) preparing?.await() else null
            suspend fun intact() = suspendSyncAt(block.location, voiceServer, plugin) {
                block.type == Material.JUKEBOX && chest.block.state is Chest &&
                    block.asJukebox()?.record?.type == Material.AIR
            }
            suspend fun start(index: Int, nextControls: PlaybackControls, overlap: Boolean, prepared: PreparedMix? = null, discOverride: ItemStack? = null): Job? =
                suspendSyncAt(block.location, voiceServer, plugin) {
                    if (chest.block.state !is Chest || block.type != Material.JUKEBOX ||
                        block.asJukebox()?.record?.type != Material.AIR || (!overlap && jukeboxes.isPlaying(block))) null
                    else {
                        state.currentIndex = index
                        val disc = discOverride ?: liveDiscs.getOrNull(index) ?: return@suspendSyncAt null
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
                controls.setMixBassGain(1.0)
                controls.setMixPasses()
                recoveryGain = controls.currentGain; recoveryTempo = controls.mixTempoFactor; recoveryStart = controls.sentAudioMs
                suspendSyncAt(block.location, voiceServer, plugin) { jukeboxes.restorePlayback(block, old, controls) }
                return true
            }
            suspend fun transition(target: Int, plan: MixPlan?, manual: Boolean): Boolean {
                controls.goodAutoMixUpcoming = false
                controls.autoMixStatus = null
                mixCommitment.clear()
                // Repeated skips while loading retain the song that is actually audible.
                if (controls.sentAudioMs == 0L && outgoing != null) recoverOutgoing()
                controls.transitionPartner = null
                outgoing?.cancelAndJoin(); outgoing = null; oldControls = null
                val ready = prepared(target)
                val incomingFeatures = ready?.let { AutoMix.chooseSection(controls.mixAnalysis.snapshot(), it.sections,
                    it.track.duration, controls.filterConfig.speed * controls.filterConfig.tempo) }
                val mixPlan = plan ?: if (autoMixEnabled(block) && incomingFeatures != null)
                    AutoMix.plan(controls.duration, ready!!.track.duration, controls.mixAnalysis.snapshot(), incomingFeatures,
                        controls.playbackRate, controls.filterConfig.speed * controls.filterConfig.tempo) else null
                val fade = mixPlan?.durationMs ?: fadeDuration()
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
                    nextControls.setMixBassGain(if (mixPlan?.beatAligned == true) 0.0 else 1.0)
                    previousControls.setMixBassGain(1.0)
                    previousControls.setMixPasses()
                    nextControls.setMixPasses(highHz = if (mixPlan?.beatAligned == true) 800.0 else 0.0)
                    nextControls.setMixTempoFactor(mixPlan?.tempoFactor ?: 1.0)
                    if (controls.paused) nextControls.togglePause()
                    nextControls.transitionPartner = previousControls
                    val next = start(target, nextControls, true, ready) ?: return false
                    envelope = MixEnvelope(fade, previousControls.currentGain, mixPlan?.incomingGain ?: 1.0,
                        mixPlan?.beatAligned == true, mixPlan?.tempoFactor ?: 1.0, previousControls.mixTempoFactor)
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
                    if (restarting.remove(block)) {
                        // During an incoming load, retain the song that is still audible.
                        if (controls.sentAudioMs == 0L && outgoing != null) recoverOutgoing()
                        val resumed = controls.copyForRestart()
                        val resumeDisc = activeDisc
                        controls.transitionPartner = null
                        preparing?.cancelAndJoin(); preparing = null; preparingDisc = null; preparationFailed = false
                        current?.cancelAndJoin()
                        outgoing?.cancelAndJoin(); outgoing = null; oldControls = null; outgoingDisc = null; envelope = null
                        mixCommitment.clear()
                        fadeOutStart = null; haltAtEnd = false
                        recoveryGain = 1.0; recoveryTempo = 1.0; recoveryStart = 0L
                        skipping.remove(block); jumpTargets.remove(block)
                        controls = resumed
                        current = start(index, controls, false, discOverride = resumeDisc) ?: break
                        continue
                    }
                    controls.autoMixAnalysis = autoMixEnabled(block)
                    if (!controls.autoMixAnalysis) { controls.autoMixStatus = null; mixCommitment.clear() }
                    if (preparing?.isCompleted == true && preparationFailed) {
                        // Consume the completed result before touching inventory on its owning thread.
                        preparing?.await()
                        val failedDisc = preparingDisc
                        val occurrence = liveDiscs.take(preparingTarget.coerceAtLeast(0)).count { it == failedDisc }
                        if (failedDisc != null) suspendSyncAt(block.location, voiceServer, plugin) {
                            val inventory = (chest.block.state as? Chest)?.inventory ?: return@suspendSyncAt
                            val slots = (0 until inventory.size).filter { inventory.getItem(it) == failedDisc }
                            val slot = slots.getOrNull(occurrence) ?: return@suspendSyncAt
                            val blank = inventory.getItem(slot)!!.clone()
                            blank.eraseBurnMetadata(config, discHelper)
                            inventory.setItem(slot, blank)
                        }
                        preparing = null; preparingDisc = null; preparationFailed = false
                        refreshTicks = 5 // Refresh/remap the queue before selecting the following song.
                    }
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
                        controls.goodAutoMixUpcoming = false
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
                            val passes = fade.passCutoffs(controls.sentAudioMs)
                            oldControls?.setMixPasses(lowHz = passes.first)
                            controls.setMixPasses(highHz = passes.second)
                            val bass = fade.bassGains(controls.sentAudioMs)
                            oldControls?.setMixBassGain(bass.first); controls.setMixBassGain(bass.second)
                            if (fade.beatMatched) {
                                val tempos = fade.tempos(controls.sentAudioMs)
                                oldControls?.setMixTempoFactor(tempos.first)
                                controls.setMixTempoFactor(tempos.second)
                            }
                        }
                        // No fade clock advances until incoming audio is transmitted, including live streams.
                        if (fade.complete(controls.sentAudioMs) || outgoing?.isActive != true) {
                            outgoing?.cancelAndJoin(); outgoing = null; oldControls = null; outgoingDisc = null
                            controls.transitionPartner = null
                            controls.setMixBassGain(1.0)
                            controls.setMixPasses()
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
                        if (controls.failure != null && (activeDisc.identifier()?.startsWith("jellyfin:") == true ||
                            PlaybackFailure.keepDisc(controls.failure))) break
                        val target = order.next(liveDiscs.size, index, playbackModes(block).first,
                            playbackModes(block).second, removedNextIndex) ?: break
                        if (!transition(target, null, true)) break
                        continue
                    }
                    if (outgoing != null || haltAtEnd) {
                        controls.goodAutoMixUpcoming = false
                        controls.autoMixStatus = if (outgoing != null) "AutoMix: transition in progress" else "AutoMix: no next transition"
                        continue
                    }
                    val nextIndex = order.peek(liveDiscs.size, index, playbackModes(block).first,
                        playbackModes(block).second, removedNextIndex)
                    if (nextIndex == null) {
                        mixCommitment.clear()
                        controls.autoMixStatus = "AutoMix: no next song"
                        controls.goodAutoMixUpcoming = false
                        continue
                    }
                    val remaining = controls.duration - controls.position
                    // AutoMix previews the next intro from the beginning of this song,
                    // so listeners can see a promising match before the fade approaches.
                    if (controls.duration > 0 && remaining > 0 &&
                        (autoMixEnabled(block) || fadeDuration() > 0 && remaining <= 45_000)) prepare(nextIndex)
                    val ready = prepared(nextIndex)
                    val incomingFeatures = ready?.let { AutoMix.chooseSection(controls.mixAnalysis.snapshot(), it.sections,
                        it.track.duration, controls.filterConfig.speed * controls.filterConfig.tempo) }
                    val candidatePlan = if (autoMixEnabled(block)) AutoMix.plan(controls.duration,
                        ready?.track?.duration ?: 0L, controls.mixAnalysis.snapshot(), incomingFeatures, controls.playbackRate,
                        controls.filterConfig.speed * controls.filterConfig.tempo)
                        else MixPlan((controls.duration - fadeDuration()).coerceAtLeast(0), fadeDuration())
                    val context = ready?.takeIf { autoMixEnabled(block) && controls.seekable && !controls.isStream }?.let {
                        MixCommitKey(controls, it.disc.customDiscIdentifier(discHelper)!!,
                            liveDiscs.take(nextIndex).count { disc -> disc.customDiscIdentifier(discHelper) == it.disc.customDiscIdentifier(discHelper) },
                            controls.filterConfig.speed * controls.filterConfig.tempo)
                    }
                    val committedPlan = mixCommitment.resolve(context, candidatePlan)
                    val committedSection = ready?.sections?.minByOrNull {
                        kotlin.math.abs((it.mixCueMs ?: it.audibleStartMs) - committedPlan.cueMs)
                    }
                    val earlierPlan = AutoMix.earlierExit(committedPlan, controls.duration, controls.position,
                        controls.mixAnalysis.snapshot(), committedSection, controls.playbackRate,
                        controls.filterConfig.speed * controls.filterConfig.tempo)
                    val plan = mixCommitment.resolve(context, earlierPlan)
                    controls.goodAutoMixUpcoming = autoMixEnabled(block) && ready != null && plan.beatAligned &&
                        controls.seekable && !controls.isStream && remaining > 0
                    controls.autoMixStatus = when {
                        !controls.seekable || controls.isStream -> "AutoMix: unavailable for live/non-seekable audio"
                        preparationFailed -> "AutoMix: skipping unavailable next song"
                        ready == null -> "AutoMix: analyzing next song"
                        plan.beatAligned -> plan.readyDescription
                        incomingFeatures == null -> "AutoMix: intro analysis unavailable; smooth fade"
                        controls.mixAnalysis.snapshot().beatMs == null -> "AutoMix: current beat uncertain; listening further"
                        incomingFeatures.beatMs == null -> "AutoMix: next intro beat uncertain; smooth fade"
                        !plan.beatAligned -> "AutoMix: tempos too far apart; smooth fade"
                        else -> plan.readyDescription
                    }
                    if (controls.paused) continue
                    if (plan.durationMs > 0 && controls.seekable && !controls.isStream && controls.duration > 0 &&
                        controls.position >= plan.startMs && remaining > 0) {
                        val target = order.next(liveDiscs.size, index, playbackModes(block).first,
                            playbackModes(block).second, removedNextIndex) ?: continue
                        if (!transition(target, plan, false)) break
                    }
                }
            } finally {
                controls.goodAutoMixUpcoming = false
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
            if (queues.remove(block, queue)) { skipping.remove(block); jumpTargets.remove(block); restarting.remove(block) }
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
        restarting.remove(block)
        restarting.remove(block.getRelative(BlockFace.DOWN))
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
            val index = queueStates[block]?.currentIndex ?: 0
            val discs = (block.getRelative(BlockFace.UP).state as? Chest)?.inventory?.contents
                ?.filterNotNull()?.filter { it.isCustomDisc(discHelper) }.orEmpty()
            val controls = jukeboxes.controlsByBlock[block]
            discs.getOrNull(index)?.let { disc ->
                pendingLoopQueues[block] = QueueResume(index, disc.clone(), controls?.position ?: 0L, controls?.paused ?: false)
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
                    val resume = pendingLoopQueues.remove(block) ?: return@forEach
                    if (isQueued(block) || jukeboxes.isPlaying(block)) return@forEach
                    val chest = block.getRelative(BlockFace.UP).state as? Chest ?: return@forEach
                    if (block.type == Material.JUKEBOX && block.asJukebox()?.record?.type == Material.AIR) {
                        val discs = chest.inventory.contents.filterNotNull().filter { it.isCustomDisc(discHelper) }
                        val index = if (discs.getOrNull(resume.index) == resume.disc) resume.index
                            else discs.indexOf(resume.disc)
                        if (index >= 0) startChestQueue(block, chest, initialIndex = index,
                            initialPositionMs = resume.positionMs, initiallyPaused = resume.paused)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
}


