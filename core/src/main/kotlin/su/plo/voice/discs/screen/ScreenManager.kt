package su.plo.voice.discs.screen

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.discs.AddonConfig
import su.plo.voice.discs.PlasmoAudioPlayerManager
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.getter
import su.plo.voice.discs.utils.extend.getValue
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ScreenManager : PluginKoinComponent {

    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val config: AddonConfig by getter()
    private val audioPlayerManager: PlasmoAudioPlayerManager by getter()

    private val screens = ConcurrentHashMap<String, Screen>()
    private val activePlayers = ConcurrentHashMap<String, VideoPlayer>()
    private val selectionSessions = ConcurrentHashMap<UUID, ScreenSelectionSession>()

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val screensFile: File
        get() = File(plugin.dataFolder, "screens.json")

    init {
        loadScreens()
        scope.launch {
            if (config.screen.enabled && config.screen.autoInstallFfmpeg) {
                FFmpegResolver.resolve(plugin.dataFolder, config.screen.ffmpegPath, autoInstall = true)
            }
        }
    }

    fun shutdown() {
        activePlayers.values.forEach { it.stop() }
        activePlayers.clear()
        selectionSessions.clear()
    }

    fun loadScreens() {
        try {
            val file = screensFile
            if (!file.exists()) return

            val json = file.readText()
            val type = object : TypeToken<Map<String, Screen>>() {}.type
            val loaded: Map<String, Screen> = gson.fromJson(json, type) ?: return

            screens.clear()
            for ((k, v) in loaded) {
                screens[k.lowercase(Locale.ROOT)] = v
            }
        } catch (e: Exception) {
            plugin.slF4JLogger.error("Failed to load screens from $screensFile", e)
        }
    }

    fun saveScreens() {
        try {
            val file = screensFile
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
            val json = gson.toJson(screens)
            file.writeText(json)
        } catch (e: Exception) {
            plugin.slF4JLogger.error("Failed to save screens to $screensFile", e)
        }
    }

    fun startSelection(player: Player, screenName: String, preLinkedJukebox: Block? = null): Boolean {
        val lower = screenName.lowercase(Locale.ROOT)
        if (screens.containsKey(lower)) {
            player.sendMessage(Component.text("A screen named '$screenName' already exists.", NamedTextColor.RED))
            return false
        }

        val session = ScreenSelectionSession(player.uniqueId, screenName, preLinkedJukebox) { completedSession ->
            completeSelection(completedSession)
        }
        selectionSessions[player.uniqueId] = session

        if (preLinkedJukebox != null) {
            player.sendMessage(Component.text("Started screen setup for this Jukebox ('$screenName').", NamedTextColor.GREEN))
        } else {
            player.sendMessage(Component.text("Started screen creation for '$screenName'.", NamedTextColor.GREEN))
        }
        player.sendMessage(Component.text("Right-click the first corner item frame of the screen wall.", NamedTextColor.YELLOW))
        return true
    }

    fun getSelectionSession(playerUuid: UUID): ScreenSelectionSession? {
        val session = selectionSessions[playerUuid] ?: return null
        if (session.isExpired()) {
            selectionSessions.remove(playerUuid)
            return null
        }
        return session
    }

    fun cancelSelection(player: Player): Boolean {
        val removed = selectionSessions.remove(player.uniqueId)
        if (removed != null) {
            player.sendMessage(Component.text("Cancelled screen selection.", NamedTextColor.YELLOW))
            return true
        }
        return false
    }

    fun finishSelection(player: Player): Boolean {
        val session = getSelectionSession(player.uniqueId) ?: return false
        return session.finishWithoutJukebox()
    }

    private fun completeSelection(session: ScreenSelectionSession) {
        selectionSessions.remove(session.playerUuid)
        val result = session.detectionResult ?: return
        val player = Bukkit.getPlayer(session.playerUuid)

        val world = session.corner1?.world ?: return
        val screenName = session.screenName
        val lower = screenName.lowercase(Locale.ROOT)

        // Allocate and setup maps for each item frame
        val frameUuids = ArrayList<List<String>>(result.rows)
        val mapIds = ArrayList<List<Int>>(result.rows)

        for (r in 0 until result.rows) {
            val uuidRow = ArrayList<String>(result.columns)
            val idRow = ArrayList<Int>(result.columns)

            for (c in 0 until result.columns) {
                val frame = result.orderedFrames[r][c]
                uuidRow.add(frame.uniqueId.toString())

                val existingMapId = (frame.item.itemMeta as? MapMeta)?.takeIf { it.hasMapId() }?.mapId
                val mapId = if (existingMapId != null) {
                    existingMapId
                } else {
                    val mapView = Bukkit.createMap(world)
                    try {
                        mapView.renderers.toList().forEach { mapView.removeRenderer(it) }
                    } catch (_: Exception) {}
                    val mapItem = ItemStack(Material.FILLED_MAP)
                    val meta = mapItem.itemMeta as? MapMeta
                    meta?.mapView = mapView
                    mapItem.itemMeta = meta
                    frame.setItem(mapItem, false)
                    mapView.id
                }
                idRow.add(mapId)

                try {
                    frame.isFixed = true
                } catch (_: Throwable) {}
            }
            frameUuids.add(uuidRow)
            mapIds.add(idRow)
        }

        val screen = Screen(
            name = screenName,
            worldName = world.name,
            facingName = result.facing.name,
            columns = result.columns,
            rows = result.rows,
            frameUuids = frameUuids,
            mapIds = mapIds,
            jukeboxWorld = session.linkedJukebox?.world?.name,
            jukeboxX = session.linkedJukebox?.x,
            jukeboxY = session.linkedJukebox?.y,
            jukeboxZ = session.linkedJukebox?.z,
            centerX = result.centerX,
            centerY = result.centerY,
            centerZ = result.centerZ
        )

        screens[lower] = screen
        saveScreens()

        player?.sendMessage(
            Component.text(
                "Screen '$screenName' (${result.columns}x${result.rows}) created successfully" +
                        if (screen.hasLinkedJukebox()) " and linked to jukebox at (${screen.jukeboxX}, ${screen.jukeboxY}, ${screen.jukeboxZ})!" else "!",
                NamedTextColor.GREEN
            )
        )
    }

    fun deleteScreen(screenName: String): Boolean {
        val lower = screenName.lowercase(Locale.ROOT)
        val screen = screens.remove(lower) ?: return false
        stopVideo(screen)
        clearScreen(screen)
        clearItemFrames(screen)
        saveScreens()
        return true
    }

    fun getScreen(screenName: String): Screen? = screens[screenName.lowercase(Locale.ROOT)]

    fun getAllScreens(): Collection<Screen> = screens.values

    fun getScreenForJukebox(block: Block): Screen? {
        return screens.values.firstOrNull { it.isLinkedTo(block) }
    }

    fun getScreenForFrame(frameUuid: UUID): Screen? {
        return screens.values.firstOrNull { it.containsFrameUuid(frameUuid) }
    }

    fun linkJukebox(screenName: String, block: Block): Boolean {
        val screen = getScreen(screenName) ?: return false
        screen.linkJukebox(block)
        initializeItemFrames(screen, block.world)
        saveScreens()
        return true
    }

    fun unlinkJukebox(screenName: String): Boolean {
        return deleteScreen(screenName)
    }

    fun clearItemFrames(screen: Screen) {
        val world = Bukkit.getWorld(screen.worldName) ?: return
        for (r in 0 until screen.rows) {
            for (c in 0 until screen.columns) {
                val uuidStr = screen.frameUuids.getOrNull(r)?.getOrNull(c) ?: continue
                val uuid = try { UUID.fromString(uuidStr) } catch (_: Exception) { null } ?: continue
                val entity = Bukkit.getEntity(uuid) as? ItemFrame ?: continue
                entity.setItem(ItemStack(Material.AIR), false)
                try {
                    entity.isFixed = false
                } catch (_: Throwable) {}
            }
        }
    }

    private val requestedPositions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun onJukeboxPlay(
        block: Block,
        identifier: String,
        artworkUrl: String? = null,
        isYouTubeMusic: Boolean = false
    ) {
        val screen = getScreenForJukebox(block) ?: return
        playVideo(screen, identifier, 0L, artworkUrl, isYouTubeMusic)
    }

    fun onJukeboxStop(block: Block) {
        val screen = getScreenForJukebox(block) ?: return
        stopVideo(screen)
    }

    fun onJukeboxPause(block: Block, paused: Boolean) {
        val screen = getScreenForJukebox(block) ?: return
        val player = activePlayers[screen.name.lowercase(Locale.ROOT)] ?: return
        player.isPaused = paused
    }

    fun onJukeboxSeek(block: Block, positionMs: Long) {
        val screen = getScreenForJukebox(block) ?: return
        val lower = screen.name.lowercase(Locale.ROOT)
        requestedPositions[lower] = positionMs.coerceAtLeast(0)
        activePlayers[lower]?.seek(positionMs)
    }

    fun playVideo(
        screen: Screen,
        identifier: String,
        startPositionMs: Long = 0L,
        artworkUrl: String? = null,
        isYouTubeMusic: Boolean = false
    ) {
        val lower = screen.name.lowercase(Locale.ROOT)
        stopVideo(screen)
        requestedPositions[lower] = startPositionMs

        scope.launch {
            if (!config.screen.enabled) return@launch

            val videoId = VideoSourceResolver.extractYouTubeVideoId(identifier)
            val fallbackImg = artworkUrl ?: videoId?.let { VideoSourceResolver.getYouTubeThumbnailUrl(it) }

            val resolvedUrl = VideoSourceResolver.resolveVideoUrl(
                identifier = identifier,
                audioPlayerManager = audioPlayerManager,
                ytdlpPath = config.screen.ytdlpPath,
                artworkUrl = artworkUrl,
                isYouTubeMusic = isYouTubeMusic
            )

            val resolvedFfmpegPath = FFmpegResolver.resolve(
                dataFolder = plugin.dataFolder,
                configuredPath = config.screen.ffmpegPath,
                autoInstall = config.screen.autoInstallFfmpeg
            ) ?: config.screen.ffmpegPath

            lateinit var videoPlayer: VideoPlayer
            videoPlayer = VideoPlayer(
                screen = screen,
                sourceUrl = resolvedUrl,
                fps = config.screen.fps.coerceIn(5, 30),
                maxDistance = config.screen.maxDistance.coerceIn(8, 128),
                ffmpegPath = resolvedFfmpegPath,
                fallbackImageUrl = fallbackImg,
                coroutineScope = scope,
                onCompletion = {
                    activePlayers.remove(lower, videoPlayer)
                }
            )

            plugin.slF4JLogger.info(
                "Screen '${screen.name}' playback starting: source='${VideoPlayer.describeSource(resolvedUrl)}', ffmpeg='$resolvedFfmpegPath'"
            )

            activePlayers[lower] = videoPlayer
            videoPlayer.start(requestedPositions[lower] ?: startPositionMs)
        }
    }

    fun stopVideo(screen: Screen) {
        val lower = screen.name.lowercase(Locale.ROOT)
        requestedPositions.remove(lower)
        val player = activePlayers.remove(lower)
        player?.stop()
    }

    fun clearScreen(screen: Screen) {
        stopVideo(screen)
        val dummyPlayer = VideoPlayer(
            screen = screen,
            sourceUrl = "",
            coroutineScope = scope
        )
        dummyPlayer.clearScreen()
    }

    fun initializeItemFrames(screen: Screen, world: World) {
        for (r in 0 until screen.rows) {
            for (c in 0 until screen.columns) {
                val uuidStr = screen.frameUuids.getOrNull(r)?.getOrNull(c) ?: continue
                val uuid = try { UUID.fromString(uuidStr) } catch (_: Exception) { null } ?: continue
                val entity = Bukkit.getEntity(uuid) as? ItemFrame ?: continue
                val mapId = screen.mapIds.getOrNull(r)?.getOrNull(c) ?: continue

                val mapItem = ItemStack(Material.FILLED_MAP)
                val meta = mapItem.itemMeta as? MapMeta
                meta?.mapId = mapId
                mapItem.itemMeta = meta
                entity.setItem(mapItem, false)
                try {
                    entity.isFixed = true
                } catch (_: Throwable) {}
            }
        }
    }
}
