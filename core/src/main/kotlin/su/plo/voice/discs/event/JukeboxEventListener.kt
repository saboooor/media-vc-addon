package su.plo.voice.discs.event

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Block
import org.bukkit.block.Jukebox
import org.bukkit.entity.Player
import org.bukkit.entity.minecart.HopperMinecart
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.chat.style.McTextStyle
import su.plo.slib.api.server.position.ServerPos3d
import su.plo.voice.api.logging.DebugLogger
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.discs.AddonConfig
import su.plo.voice.discs.PlaybackControls
import su.plo.voice.discs.PlasmoAudioPlayerManager
import su.plo.voice.discs.api.PlayTrackFromDiscEvent
import su.plo.voice.discs.bridge.VoiceBridgeCompat
import su.plo.voice.discs.item.DiscHelper
import su.plo.voice.discs.screen.ScreenManager
import su.plo.voice.discs.svc.SvcDiscsPlugin
import su.plo.voice.discs.utils.DiscChunkUnloadCause
import su.plo.voice.discs.utils.DiscEjectCause
import su.plo.voice.discs.utils.DiscPullCause
import su.plo.voice.discs.utils.DiscReplaceCause
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import java.util.concurrent.ConcurrentHashMap

class JukeboxEventListener : Listener, PluginKoinComponent {

    private val config: AddonConfig by getter()
    private val plugin: JavaPlugin by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val scope: CoroutineScope by inject()
    private val audioPlayerManager: PlasmoAudioPlayerManager by getter()
    private val screenManager: ScreenManager? by getterOrNull<ScreenManager>()
    private val debugLogger: DebugLogger by getter()
    private val sourceLine: ServerSourceLine? by getterOrNull()
    private val svcPlugin: SvcDiscsPlugin? by getterOrNull()
    private val discHelper: DiscHelper by inject()

    var shouldLoop: (Block) -> Boolean = { false }

    private val savedVolumes = ConcurrentHashMap<Block, Int>()
    fun volumeSetting(block: Block) = controlsByBlock[block]?.volume ?: savedVolumes[block] ?: 100
    fun restoreVolume(block: Block, volume: Int) { savedVolumes[block] = volume.coerceIn(0, 100) }
    // 0: mono/default, 1/2: note block + jukebox, 3: two note blocks.
    private val stereoModes = ConcurrentHashMap<Block, Int>()
    fun stereoMode(block: Block) = stereoModes[block] ?: 0
    fun restoreStereo(block: Block, mode: Int) { stereoModes[block] = if (mode == 3) 3 else 0 }
    fun cycleStereo(block: Block) { restoreStereo(block, if (stereoMode(block) == 3) 0 else 3) }
    private val noteLinks = ConcurrentHashMap<Block, JukeboxBassLamps.Position>()
    fun noteLink(block: Block) = noteLinks[block]
    fun linkNote(block: Block, position: JukeboxBassLamps.Position?) {
        if (position == null) noteLinks.remove(block) else noteLinks[block] = position
    }
    private val leftNoteLinks = ConcurrentHashMap<Block, JukeboxBassLamps.Position>()
    private val rightNoteLinks = ConcurrentHashMap<Block, JukeboxBassLamps.Position>()
    fun channelNoteLink(block: Block, left: Boolean) = (if (left) leftNoteLinks else rightNoteLinks)[block]
    fun linkChannelNote(block: Block, left: Boolean, position: JukeboxBassLamps.Position?) {
        val links = if (left) leftNoteLinks else rightNoteLinks
        if (position == null) links.remove(block) else links[block] = position
    }
    fun clearVolume(block: Block) { savedVolumes.remove(block); stereoModes.remove(block); noteLinks.remove(block); leftNoteLinks.remove(block); rightNoteLinks.remove(block) }
    val controlsByBlock: MutableMap<Block, PlaybackControls> = ConcurrentHashMap()

    fun stopPlayback(block: Block) { jobByBlock.remove(block)?.cancel(DiscEjectCause()) }

    private val jobByBlock: MutableMap<Block, Job> = ConcurrentHashMap()

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent): Unit {
        event.chunk.getTileEntities({ it.isJukebox() }, true)
            .forEach {
                val jukebox = it as? Jukebox ?: return@forEach
                if (!it.record.isCustomDisc(discHelper)) return@forEach

                jukebox.stopPlaying()
            }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk

        jobByBlock.keys
            .filter { it.inChunk(chunk) }
            .forEach {
                jobByBlock.remove(it)?.cancel(DiscChunkUnloadCause())
            }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDiscInsert(event: PlayerInteractEvent) {

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        if (event.player.gameMode == GameMode.ADVENTURE && Bukkit.getServer().getMinecraftVersionInt() < 12100) return

        val block = event.clickedBlock ?: return

        val jukebox = block.asJukebox() ?: return

        if (jukebox.record.type != Material.AIR) return

        val item = event.item?.takeIf { it.isCustomDisc(discHelper) } ?: return

        val player = event.player
            .takeIf {
                Bukkit.getServer().getMinecraftVersionInt() < 12100 || !it.isSneaking
            } ?: return

        if (!player.hasPermission("pv.addon.discs.play") && !player.hasPermission("vc.addon.discs.play")) return

        val identifier = item.customDiscIdentifier(discHelper) ?: return

        val isPvPlayer = (svcPlugin?.hasClient(player.uniqueId) != true && VoiceBridgeCompat.isPlayerListeningOnPv(player.uniqueId, voiceServer))
        val voicePlayer = if (isPvPlayer) voiceServer?.let { player.asVoicePlayer(it) } else null

        if (voicePlayer != null) {
            voicePlayer.instance.sendActionBar(
                McTextComponent.translatable("pv.addon.discs.actionbar.loading")
                    .withStyle(McTextStyle.YELLOW)
            )
        } else {
            player.sendActionBar(Component.text("Loading...", NamedTextColor.YELLOW))
        }

        jobByBlock[block]?.cancel(DiscReplaceCause())
        val playbackJob = playTrack(identifier, block, item.itemMeta, voicePlayer, player)
        jobByBlock[block] = playbackJob
        playbackJob.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                plugin.slF4JLogger.error("Failed to play disc at ${block.location}", failure)
                scope.launch {
                    suspendSyncFor(player, voiceServer, plugin) {
                        player.sendActionBar(Component.text("Disc playback failed. Check the server console.", NamedTextColor.RED))
                    }
                }
            }
            jobByBlock.remove(block, playbackJob)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDiskEject(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        if (
            (event.player.inventory.itemInMainHand.type != Material.AIR ||
                    event.player.inventory.itemInOffHand.type != Material.AIR) &&
            event.player.isSneaking
        ) return

        val block = event.clickedBlock ?: return

        block.asJukebox()?.takeIf { it.isPlaying } ?: return

        jobByBlock.remove(block)?.cancel(DiscEjectCause())
    }

    @EventHandler
    fun onJukeboxBreak(event: BlockBreakEvent) {
        event.block
            .takeIf { it.isJukebox() }
            ?.also { block ->
                block.asJukebox()?.stopPlaying()
                val sm = screenManager ?: return@also
                sm.getScreenForJukebox(block)?.let { screen ->
                    sm.unlinkJukebox(screen.name)
                }
            }
            ?.let { jobByBlock.remove(it) }
            ?.cancel(DiscEjectCause())
    }

    @EventHandler
    fun onJukeboxExplode(event: EntityExplodeEvent) {
        val sm = screenManager
        event.blockList()
            .filter { it.isJukebox() }
            .forEach { block ->
                sm?.getScreenForJukebox(block)?.let { screen ->
                    sm.unlinkJukebox(screen.name)
                }
                jobByBlock.remove(block)?.cancel(DiscEjectCause())
            }
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        val sm = screenManager
        event.blockList()
            .filter { it.isJukebox() }
            .forEach { block ->
                sm?.getScreenForJukebox(block)?.let { screen ->
                    sm.unlinkJukebox(screen.name)
                }
                jobByBlock.remove(block)?.cancel(DiscEjectCause())
            }
    }

    fun isPlaying(block: Block): Boolean =
        jobByBlock.containsKey(block)

    fun playStoredDisc(block: Block, player: Player) {
        val record = block.asJukebox()?.record ?: return
        val identifier = record.customDiscIdentifier(discHelper) ?: return
        if (!isPlaying(block)) playChestTrack(identifier, block, record.itemMeta, player, chestQueue = false)
    }

    fun playChestTrack(identifier: String, block: Block, itemMeta: ItemMeta?, player: Player? = null, chestQueue: Boolean = true,
                       controls: PlaybackControls = PlaybackControls(), overlap: Boolean = false, onFailure: (() -> Unit)? = null): Job {
        check(overlap || !isPlaying(block)) { "Jukebox is already playing" }
        val voicePlayer = player?.let { p -> voiceServer?.let { p.asVoicePlayer(it) } }
        val job = playTrack(identifier, block, itemMeta, voicePlayer, player, chestQueue = chestQueue, controls = controls, onFailure = onFailure)
        jobByBlock[block] = job
        job.invokeOnCompletion { jobByBlock.remove(block, job) }
        return job
    }

    private fun playTrack(
        identifier: String,
        block: Block,
        itemMeta: ItemMeta?,
        voicePlayer: VoicePlayer? = null,
        player: Player? = null,
        chestQueue: Boolean = false,
        controls: PlaybackControls = PlaybackControls(),
        onFailure: (() -> Unit)? = null,
    ): Job {
        savedVolumes[block]?.let { controls.changeVolume(it - controls.volume) }
        controls.onVolumeChanged = { savedVolumes[block] = it }
        val playback = scope.launch(start = CoroutineStart.LAZY) {

            suspend fun failed(message: String) {
                controls.fail(message)
                controls.title = "Failed: $message"
                suspendSyncAt(block.location, voiceServer, plugin) {
                    val jellyfin = identifier.startsWith("jellyfin:")
                    val error = Component.text(if (jellyfin) "Could not play Jellyfin media. Check the connection; the disc was kept."
                        else "Could not play song: $message. Disc unburned; skipping.", NamedTextColor.RED)
                    player?.sendMessage(error)
                    player?.sendActionBar(error)
                    if (!jellyfin) onFailure?.invoke()
                    if (!chestQueue && !jellyfin) {
                        val jukebox = block.asJukebox() ?: return@suspendSyncAt
                        val record = jukebox.record
                        if (record.customDiscIdentifier(discHelper) == identifier && record.itemMeta == itemMeta) {
                            record.eraseBurnMetadata(config, discHelper)
                            jukebox.setRecord(record)
                            jukebox.update()
                            jukebox.eject()
                        }
                    }
                }
            }
            val track = try {
                withTimeout(30_000L) { audioPlayerManager.getTrack(identifier).await() }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                currentCoroutineContext().ensureActive()
                val errorMsg = if (e is TimeoutCancellationException) "Audio lookup timed out after 30 seconds" else e.message ?: "Unexpected error"
                plugin.slF4JLogger.error("Failed to load disc track", e)
                failed(errorMsg)
                return@launch
            }

            if (voicePlayer != null) {
                if (!suspendSyncAt(block.location, voiceServer, plugin) {
                    PlayTrackFromDiscEvent(voicePlayer, track, block).callEvent()
                }) return@launch
            }

            val trackName = (itemMeta
                ?.lore()
                ?.getOrNull(0)
                ?.let { it as? TextComponent }
                ?.content()
                ?: track.info.title).cleanTrackName()

            val cleanAuthor = track.info.author.cleanArtistName()

            val titleHasArtist = cleanAuthor.isNotBlank() &&
                (trackName.containsArtist(cleanAuthor) || track.info.title.containsArtist(cleanAuthor))

            val displayTitle = if (cleanAuthor.isNotBlank() && !titleHasArtist) {
                "$cleanAuthor - $trackName"
            } else {
                trackName
            }

            controls.title = displayTitle

            val distance: Short = when (config.distance.enableBeaconLikeDistance) {
                true -> {
                    val beaconLevel = suspendSyncAt(block.location, voiceServer, plugin) {
                        getBeaconLevel(block)
                    }
                    config.distance.beaconLikeDistanceList[beaconLevel]
                }

                false -> config.distance.jukeboxDistance
            }

            val mode = stereoMode(block)
            val speakers = suspendSyncAt(block.location, voiceServer, plugin) {
                fun resolve(position: JukeboxBassLamps.Position?) = position?.loadedBlock()?.takeIf {
                    it.type == Material.NOTE_BLOCK && it.world == block.world &&
                        it.location.distanceSquared(block.location) <= 1024
                }
                if (mode == 3) {
                    resolve(channelNoteLink(block, true)) to resolve(channelNoteLink(block, false))
                } else {
                    block to null
                }
            }
            if (mode == 3 && (speakers.first == null || speakers.second == null || speakers.first == speakers.second)) {
                val message = "Link two distinct, available note blocks for left and right stereo playback"
                controls.fail(message)
                suspendSyncAt(block.location, voiceServer, plugin) {
                    player?.sendMessage(Component.text(message, NamedTextColor.RED))
                }
                return@launch
            }
            val primarySpeaker = speakers.first ?: block
            val noteBlock = speakers.second
            fun pvAt(speaker: Block) = if (sourceLine != null && voiceServer != null) {
                val world = voiceServer?.minecraftServer?.getWorld(speaker.world)
                val pos = ServerPos3d(world, speaker.x + 0.5, speaker.y + 1.5, speaker.z + 0.5)
                if (config.distance.visualizeDistance) voicePlayer?.visualizeDistance(pos.toPosition(), distance.toInt(), 0xf1c40f)
                sourceLine?.createStaticSource(pos, if (noteBlock != null) false else !config.monoSources)?.apply { setName(trackName) }
            } else null
            fun svcAt(speaker: Block) = svcPlugin?.createLocationalChannel(
                Location(speaker.world, speaker.x + 0.5, speaker.y + 1.5, speaker.z + 0.5), distance.toFloat()
            )?.apply { setFilter { sp -> svcPlugin?.hasClient(sp.uuid) == true } }
            val pvSource = pvAt(primarySpeaker)
            val svcChannel = svcAt(primarySpeaker)
            val notePvSource = noteBlock?.let { pvAt(it) }
            val noteSvcChannel = noteBlock?.let { svcAt(it) }

            val actionbarMessage = McTextComponent.translatable(
                "pv.addon.discs.actionbar.playing", displayTitle
            )

            debugLogger.log("Starting track job \"$displayTitle\" with distance $distance at ${block.location}")

            suspendSyncAt(block.location, voiceServer, plugin) { block.world.getNearbyPlayers(block.location, distance.toDouble()) }
                .forEach { nearby ->
                    if ((svcPlugin?.hasClient(nearby.uniqueId) != true && VoiceBridgeCompat.isPlayerListeningOnPv(nearby.uniqueId, voiceServer))) {
                        val vp = voiceServer?.let { nearby.asVoicePlayer(it) }
                        vp?.sendAnimatedActionBar(actionbarMessage)
                    } else {
                        nearby.sendActionBar(Component.text("Now playing: $displayTitle", NamedTextColor.GOLD))
                    }
                }

            val job = if (noteBlock == null) audioPlayerManager.startTrackJob(
                track = track, pvSource = pvSource, distance = distance,
                svcChannel = svcChannel, controls = controls
            ) else {
                audioPlayerManager.startStereoTrackJob(
                    track = track,
                    leftPvSource = pvSource,
                    rightPvSource = notePvSource,
                    distance = distance,
                    leftSvcChannel = svcChannel,
                    rightSvcChannel = noteSvcChannel,
                    controls = controls
                )
            }
            controls.onPauseChanged = { isPaused ->
                screenManager?.onJukeboxPause(block, isPaused)
            }
            controls.onSeek = { targetMs ->
                screenManager?.onJukeboxSeek(block, targetMs)
            }
            val videoIdentifier = track.info.uri?.takeIf { it.isNotBlank() } ?: track.info.identifier?.takeIf { it.isNotBlank() } ?: identifier
            val isYouTubeMusic = track.info.author.contains("- Topic", ignoreCase = true) ||
                identifier.contains("music.youtube.com") || (track.info.uri ?: "").contains("music.youtube.com")
            screenManager?.onJukeboxPlay(block, if (identifier.startsWith("jellyfin:")) identifier else videoIdentifier,
                artworkUrl = track.info.artworkUrl, isYouTubeMusic = isYouTubeMusic,
                playbackPositionMs = { controls.position.takeIf { it > 0 } })
            try {
                var lastExistenceCheck = System.currentTimeMillis()
                var lastRecordReset = System.currentTimeMillis()
                var lastAudioCheck = System.currentTimeMillis()
                var loadingMillis = 0L

                while (job.isActive) {
                    val currentTime = System.currentTimeMillis()
                    if (!controls.paused && controls.position == 0L) loadingMillis += currentTime - lastAudioCheck
                    else loadingMillis = 0L
                    lastAudioCheck = currentTime
                    if (loadingMillis >= 30_000L) {
                        controls.fail("Audio stream did not start within 30 seconds")
                    }
                    if (controls.failure != null) break
                    val timeSinceExistenceCheck = currentTime - lastExistenceCheck
                    val timeSinceRecordReset = currentTime - lastRecordReset

                    // Check if jukebox still exists every 3 seconds
                    if (timeSinceExistenceCheck >= 3_000L) {
                        val stillExists = suspendSyncAt(block.location, voiceServer, plugin) {
                            block.type == Material.JUKEBOX &&
                                (mode != 3 || primarySpeaker.type == Material.NOTE_BLOCK) &&
                                (noteBlock == null || noteBlock.type == Material.NOTE_BLOCK)
                        }

                        if (!stillExists) {
                            debugLogger.log("Jukebox at ${block.location} no longer exists, stopping playback")
                            cancel(DiscEjectCause())
                            return@launch
                        }

                        lastExistenceCheck = System.currentTimeMillis()
                    }

                    // Reset record state every 30 seconds
                    if (!chestQueue && timeSinceRecordReset >= 30_000L) {
                        suspendSyncAt(block.location, voiceServer, plugin) {
                            val jukebox = block.asJukebox() ?: return@suspendSyncAt
                            jukebox.setRecord(jukebox.record)
                            try {
                                val startPlayingMethod = jukebox.javaClass.getMethod("startPlaying")
                                startPlayingMethod.invoke(jukebox)
                            } catch (_: ReflectiveOperationException) {
                            }
                            jukebox.update()
                        }
                        lastRecordReset = System.currentTimeMillis()
                    }

                    delay(100L)
                }
                controls.failure?.let { failed(it) }
            } catch (e: CancellationException) {
                debugLogger.log("Track \"$trackName\" at ${block.location} cancelled: ${e.message}")
                throw e
            } finally {
                withContext(NonCancellable) {
                    debugLogger.log("Track \"$trackName\" at ${block.location} ended")

                    job.cancelAndJoin()
                    notePvSource?.remove()
                    pvSource?.remove()
                    screenManager?.onJukeboxStop(block)

                    if (!scope.isActive) return@withContext

                    suspendSyncAt(block.location, voiceServer, plugin) {
                        val jukebox = block.asJukebox() ?: return@suspendSyncAt
                        val currentJob = jobByBlock[block] ?: return@suspendSyncAt
                        if (currentJob != this@launch) return@suspendSyncAt
                        jukebox.stopPlayingWithUpdate()
                    }

                    suspendSyncAt(block.location, voiceServer, plugin) {
                        val currentJob = jobByBlock[block] ?: return@suspendSyncAt
                        if (currentJob != this@launch) return@suspendSyncAt
                        jobByBlock.remove(block)
                    }
                }
            }
        }
        controlsByBlock[block] = controls
        playback.invokeOnCompletion { failure ->
            controlsByBlock.remove(block, controls)
            if (failure == null && controls.failure == null && !chestQueue && scope.isActive) scope.launch {
                delay(100)
                suspendSyncAt(block.location, voiceServer, plugin) {
                    val record = block.asJukebox()?.record ?: return@suspendSyncAt
                    if (shouldLoop(block) && !isPlaying(block) &&
                        record.customDiscIdentifier(discHelper) == identifier && record.itemMeta == itemMeta) {
                        playChestTrack(identifier, block, itemMeta, player, chestQueue = false)
                    }
                }
            }
        }
        playback.start()
        return playback
    }

    private fun getBeaconLevel(block: Block) =
        (1 until config.distance.beaconLikeDistanceList.size).takeWhile { level ->
            (-level..level).all { xOffset ->
                (-level..level).all { zOffset ->
                    Location(
                        block.world,
                        (block.x + xOffset).toDouble(),
                        (block.y - level).toDouble(),
                        (block.z + zOffset).toDouble()
                    ).block.isBeaconBaseBlock()
                }
            }
        }.count()

    inner class HopperEventListener : Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onMinecartHopperPull(event: InventoryMoveItemEvent) {
            if (event.source.type.name != "JUKEBOX") return
            if (event.destination.holder !is HopperMinecart) return

            val block = event.source.location?.block ?: return

            val item = event.item
            if (!item.isCustomDisc(discHelper)) return

            jobByBlock.remove(block)?.cancel(DiscPullCause())
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onHopperInsertToJukebox(event: InventoryMoveItemEvent) {
            if (event.destination.type.name != "JUKEBOX") return

            val block = event.destination.location?.block ?: return

            val item = event.item
            val identifier = item.customDiscIdentifier(discHelper) ?: return

            jobByBlock.remove(block)?.cancel(DiscReplaceCause())
            jobByBlock[block] = playTrack(identifier, block, item.itemMeta)
        }
    }
}

