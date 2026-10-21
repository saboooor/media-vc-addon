package su.plo.voice.discs

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.plugin.java.JavaPlugin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import su.plo.slib.api.logging.McLoggerFactory
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.api.addon.AddonLoaderScope
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.addon.injectPlasmoVoice
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.logging.DebugLogger
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.api.server.event.config.VoiceServerConfigReloadedEvent
import su.plo.voice.proto.packets.tcp.clientbound.SourceLineRegisterPacket
import su.plo.voice.discs.command.CommandHandler
import su.plo.voice.discs.command.subcommand.ControlCommand
import su.plo.voice.discs.command.subcommand.HologramCommand
import su.plo.voice.discs.command.subcommand.VisualizerCommand
import su.plo.voice.discs.command.subcommand.PlaylistCommand
import su.plo.voice.discs.jellyfin.JellyfinLibrary
import su.plo.voice.discs.command.subcommand.JellyfinCommand
import su.plo.voice.discs.command.subcommand.EffectCommand
import su.plo.voice.discs.command.subcommand.BurnCommand
import su.plo.voice.discs.command.subcommand.CancelCommand
import su.plo.voice.discs.command.subcommand.EraseCommand
import su.plo.voice.discs.command.subcommand.SearchCommand
import su.plo.voice.discs.command.subcommand.ReloadCommand
import su.plo.voice.discs.command.subcommand.OauthCommand
import su.plo.voice.discs.crafting.BurnableDiscCraft
import su.plo.voice.discs.crafting.BurnableHornCraft
import su.plo.voice.discs.event.ForbidGrindstoneListener
import su.plo.voice.discs.event.JukeboxSettingsListener
import su.plo.voice.discs.event.JukeboxBassLamps
import su.plo.voice.discs.event.JukeboxVisualizer
import su.plo.voice.discs.event.JukeboxProgressDisplay
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.screen.ScreenCommand
import su.plo.voice.discs.screen.ScreenEventListener
import su.plo.voice.discs.screen.ScreenManager
import su.plo.voice.discs.item.DiscHelper
import su.plo.voice.discs.item.GoatHornHelper
import su.plo.voice.discs.packet.CancelJukeboxPlayEvent
import su.plo.voice.discs.svc.SvcDiscsPlugin
import su.plo.voice.discs.utils.KOIN_INSTANCE
import su.plo.voice.discs.utils.LavaplayerDebugLogging
import su.plo.voice.discs.utils.extend.debug
import su.plo.voice.discs.utils.extend.getMinecraftVersionInt
import su.plo.voice.discs.utils.extend.registerBukkit
import su.plo.voice.discs.v1_19_4.GoatHornListener

@Addon(
    id = "vc-addon-discs",
    scope = AddonLoaderScope.SERVER,
    version = BuildConstants.VERSION,
    authors = ["KPidS", "Apehum"]
)
class DiscsPlugin : JavaPlugin(), Listener {

    private val addonName = "discs"

    private val voiceServer: PlasmoVoiceServer by injectPlasmoVoice()
    private var jukeboxSettings: JukeboxSettingsListener? = null
    private var bassLamps: JukeboxBassLamps? = null
    private var visualizer: JukeboxVisualizer? = null
    private var visualizerEditor: VisualizerCommand? = null
    private var progressDisplay: JukeboxProgressDisplay? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sourceLine: ServerSourceLine
    private var svcPlugin: SvcDiscsPlugin? = null
    private var screenManager: ScreenManager? = null
    private var screenMapPacketListener: com.github.retrooper.packetevents.event.PacketListenerCommon? = null

    private val pvServer: PlasmoVoiceServer?
        get() = if (isPvAvailable()) {
            try {
                voiceServer
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    private fun isPvAvailable(): Boolean {
        return try {
            Class.forName("su.plo.voice.api.server.PlasmoVoiceServer")
            server.pluginManager.getPlugin("PlasmoVoice") != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isSvcAvailable(): Boolean {
        return try {
            Class.forName("de.maxhenkel.voicechat.api.BukkitVoicechatService")
            server.pluginManager.getPlugin("voicechat") != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun registerSvc() {
        if (svcPlugin != null || !isSvcAvailable()) return
        try {
            val service = server.servicesManager.load(BukkitVoicechatService::class.java)
            if (service != null) {
                val plugin = SvcDiscsPlugin(this)
                service.registerPlugin(plugin)
                svcPlugin = plugin
                if (isEnabled) bindSvc()
                slF4JLogger.info("Successfully registered Simple Voice Chat discs integration")
            }
        } catch (e: Throwable) {
            slF4JLogger.warn("Failed to register Simple Voice Chat integration", e)
        }
    }

    private fun bindSvc() {
        svcPlugin?.let { svc ->
            KOIN_INSTANCE.loadModules(listOf(module {
                single<SvcDiscsPlugin> { svc }
            }))
        }
    }

    @EventHandler
    fun onServiceRegistered(event: ServiceRegisterEvent) {
        if (isSvcAvailable() && event.provider.service == BukkitVoicechatService::class.java) {
            registerSvc()
        }
    }

    private val discHelper: DiscHelper by lazy {
        val mcVersion = Bukkit.getServer().getMinecraftVersionInt()

        if (mcVersion >= 260300) {
            su.plo.voice.discs.v26_3.DiscHelperImpl()
        } else if (mcVersion >= 12105) {
            su.plo.voice.discs.v1_21_5.DiscHelperImpl()
        } else if (mcVersion >= 12103) {
            su.plo.voice.discs.v1_21_3.DiscHelperImpl()
        } else {
            su.plo.voice.discs.v1_19_4.DiscHelperImpl()
        } as DiscHelper
    }

    private val goatHornHelper: GoatHornHelper by lazy {
        val mcVersion = Bukkit.getServer().getMinecraftVersionInt()

        if (mcVersion >= 260300) {
            su.plo.voice.discs.v26_3.GoatHornHelperImpl()
        } else if (mcVersion >= 12105) {
            su.plo.voice.discs.v1_21_5.GoatHornHelperImpl()
        } else if (mcVersion >= 12103) {
            su.plo.voice.discs.v1_21_3.GoatHornHelperImpl()
        } else if (mcVersion >= 12006) {
            su.plo.voice.discs.v1_20_6.GoatHornHelperImpl()
        } else if (mcVersion >= 11902) {
            su.plo.voice.discs.v1_19_4.GoatHornHelperImpl()
        } else {
            throw IllegalArgumentException("Goat horns in $mcVersion is not supported!")
        } as GoatHornHelper
    }

    private val goatHornManager by lazy {
        val mcVersion = Bukkit.getServer().getMinecraftVersionInt()

        if (mcVersion >= 11902) {
            GoatHornManager()
        } else {
            throw IllegalArgumentException("Goat horns in $mcVersion is not supported!")
        }
    }

    private val jellyfinLibrary by lazy { JellyfinLibrary(dataFolder.toPath().resolve("jellyfin.json")) }

    private lateinit var audioPlayerManager: PlasmoAudioPlayerManager

    @Volatile
    private lateinit var addonConfig: AddonConfig
    private lateinit var debugLogger: DebugLogger

    override fun onLoad() {
        if (isPvAvailable()) {
            try {
                PlasmoVoiceServer.getAddonsLoader().load(this)
            } catch (e: Throwable) {
                slF4JLogger.warn("Failed to load Plasmo Voice addon: ${e.message}")
            }
        }

        registerSvc()

        val koinModule = module {
            pvServer?.let { server -> single<PlasmoVoiceServer> { server } }
            single<CoroutineScope> { scope }
            factory<AddonConfig> { addonConfig }
            factory<PlasmoAudioPlayerManager> { audioPlayerManager }
            single<GoatHornHelper> { goatHornHelper }
            single<GoatHornManager> { goatHornManager }
            single<JellyfinLibrary> { jellyfinLibrary }
            single<JavaPlugin> { this@DiscsPlugin }
            factory<DebugLogger> { debugLogger }
            single<DiscHelper> { discHelper }
            svcPlugin?.let { svc -> single<SvcDiscsPlugin> { svc } }
        }

        KOIN_INSTANCE = koinApplication {
            modules(koinModule)
        }.koin
    }

    @EventSubscribe
    fun onConfigReloaded(event: VoiceServerConfigReloadedEvent) {
        loadConfig()
    }

    override fun onEnable() {
        // SVC can enable after this STARTUP plugin and publish its service later.
        server.pluginManager.registerEvents(this, this)
        registerSvc()
        bindSvc()
        svcPlugin?.resume()
        svcPlugin?.attachToRunningServer()

        loadConfig()

        su.plo.voice.discs.utils.PlayerAudience.start(this, scope, pvServer)
        su.plo.voice.discs.screen.CompanionScreens.start(this)
        val screenManagerInstance = ScreenManager()
        screenManager = screenManagerInstance

        val jukeboxEventListener = JukeboxEventListener()
        val progressDisplayInstance = JukeboxProgressDisplay(jukeboxEventListener).also {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        progressDisplay = progressDisplayInstance
        val visualizerInstance = JukeboxVisualizer(jukeboxEventListener).also {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        visualizer = visualizerInstance
        val bassLampsInstance = JukeboxBassLamps(jukeboxEventListener).also {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        bassLamps = bassLampsInstance

        KOIN_INSTANCE.loadModules(listOf(module {
            single<ScreenManager> { screenManagerInstance }
            single<JukeboxProgressDisplay> { progressDisplayInstance }
            single<JukeboxVisualizer> { visualizerInstance }
            single<JukeboxBassLamps> { bassLampsInstance }
        }))
        server.pluginManager.registerEvents(ScreenEventListener(), this)

        val playlistCommand = PlaylistCommand(jukeboxEventListener)
        progressDisplayInstance.queueView = playlistCommand::queueView
        val visualizerCommand = VisualizerCommand(visualizerInstance).also {
            visualizerEditor = it
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        val controlCommand = ControlCommand(jukeboxEventListener, playlistCommand, visualizerCommand)
        val hologramCommand = HologramCommand(jukeboxEventListener, controlCommand, visualizerCommand)
        server.pluginManager.registerEvents(controlCommand, this)
        server.pluginManager.registerEvents(playlistCommand, this)
        server.pluginManager.registerEvents(jukeboxEventListener, this)
        jukeboxSettings = JukeboxSettingsListener(jukeboxEventListener, playlistCommand, visualizerInstance,
            progressDisplayInstance, bassLampsInstance, screenManagerInstance).also {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }

        if (Bukkit.getServer().getMinecraftVersionInt() >= 11904) {
            Bukkit.getServer().pluginManager.registerEvents(jukeboxEventListener.HopperEventListener(), this)
        }

        server.pluginManager.registerEvents(ForbidGrindstoneListener(), this)

        val hornsSupported = Bukkit.getServer().getMinecraftVersionInt() >= 11902
        if (hornsSupported) {
            server.pluginManager.registerEvents(GoatHornListener(), this)
        }

        val jellyfinCommand = JellyfinCommand(jellyfinLibrary)
        server.pluginManager.registerEvents(jellyfinCommand, this)
        val handler = CommandHandler()
            .addSubCommand(::BurnCommand)
            .addSubCommand(::EffectCommand)
            .addSubCommand { jellyfinCommand }
            .addSubCommand { playlistCommand }
            .addSubCommand { controlCommand }
            .addSubCommand { hologramCommand }
            .addSubCommand { visualizerCommand }
            .addSubCommand(::EraseCommand)
            .addSubCommand(::SearchCommand)
            .addSubCommand(::ScreenCommand)
            .addSubCommand(::OauthCommand)
            .addSubCommand { ReloadCommand(::reloadRuntimeConfig) }
        if (hornsSupported) {
            handler.addSubCommand(::CancelCommand)
        }

        val command = getCommand("disc") ?: throw Exception("Command not found")

        command.setExecutor(handler)
        command.tabCompleter = handler

        val pvPermissions = pvServer?.minecraftServer?.permissionManager
        pvPermissions?.registerBukkit("pv.addon.discs.play", PermissionDefault.TRUE)
        pvPermissions?.registerBukkit("pv.addon.discs.screen", PermissionDefault.OP)

        listOf("pv.addon.discs.play", "vc.addon.discs.play").forEach { perm ->
            if (Bukkit.getPluginManager().getPermission(perm) == null) {
                try {
                    Bukkit.getPluginManager().addPermission(
                        org.bukkit.permissions.Permission(perm, org.bukkit.permissions.PermissionDefault.TRUE)
                    )
                } catch (_: Throwable) {}
            }
        }

        listOf("pv.addon.discs.screen", "vc.addon.discs.screen").forEach { perm ->
            if (Bukkit.getPluginManager().getPermission(perm) == null) {
                try {
                    Bukkit.getPluginManager().addPermission(
                        org.bukkit.permissions.Permission(perm, org.bukkit.permissions.PermissionDefault.OP)
                    )
                } catch (_: Throwable) {}
            }
        }

        screenMapPacketListener = PacketEvents.getAPI().eventManager.registerListener(
            su.plo.voice.discs.screen.ScreenMapPacketListener(screenManagerInstance),
            PacketListenerPriority.NORMAL
        )
        PacketEvents.getAPI().eventManager.registerListener(
            CancelJukeboxPlayEvent(jukeboxEventListener),
            PacketListenerPriority.NORMAL
        )

        if (addonConfig.burnableTag.enableDefaultRecipe) {
            BurnableDiscCraft().also { it.registerRecipes() }

            val mcVersion = Bukkit.getServer().getMinecraftVersionInt()

            if (mcVersion >= 11902) {
                BurnableHornCraft().registerRecipes()
            }
        }
    }

    override fun onDisable() {
        screenMapPacketListener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        screenMapPacketListener = null
        visualizerEditor?.shutdown()
        jukeboxSettings?.shutdown()
        bassLamps?.shutdown()
        visualizer?.shutdown()
        progressDisplay?.shutdown()
        screenManager?.shutdown()
        su.plo.voice.discs.screen.CompanionScreens.shutdown()
        runBlocking {
            withTimeoutOrNull(5_000L) { scope.coroutineContext.job.cancelAndJoin() }
        }

        su.plo.voice.discs.utils.PlayerAudience.shutdown()
        su.plo.voice.discs.utils.SettingsFileWriter.shared.close()

        if (isPvAvailable()) {
            try {
                PlasmoVoiceServer.getAddonsLoader().unload(this)
            } catch (_: Throwable) {}
        }

        if (::audioPlayerManager.isInitialized) {
            audioPlayerManager.shutdown()
        }

        svcPlugin?.suspend()

        LavaplayerDebugLogging.setEnabled(false)
    }

    private fun reloadRuntimeConfig() {
        val reloaded = AddonConfig.loadConfig(pvServer, dataFolder, registerLanguages = false)
        reloaded.preserveRestartSettings(addonConfig)
        addonConfig = reloaded
        val debug = reloaded.debug || (pvServer?.debug() ?: false)
        debugLogger.enabled(debug)
        LavaplayerDebugLogging.setEnabled(debug)
    }

    private fun loadConfig() {
        addonConfig = AddonConfig.loadConfig(pvServer, dataFolder)
        debugLogger = DebugLogger(McLoggerFactory.createLogger(slF4JLogger.name))
        val debug = addonConfig.debug || (pvServer?.debug() ?: false)
        debugLogger.enabled(debug)
        LavaplayerDebugLogging.setEnabled(debug)

        pvServer?.let { server ->
            server.sourceLineManager.unregister(addonName)
            sourceLine = server.sourceLineManager.createBuilder(
                this,
                addonName,
                "pv.activation.$addonName",
                "plasmovoice:textures/icons/speaker_disc.png",
                addonConfig.sourceLineWeight
            ).apply {
                setDefaultVolume(addonConfig.defaultSourceLineVolume)
            }.build()

            // Building a line only registers it on the server. Existing clients received
            // an unregister packet during unload/reload and need the replacement too.
            server.playerManager.players.filter { it.hasVoiceChat() }.forEach { player ->
                player.sendPacket(SourceLineRegisterPacket(sourceLine.getSourceLineForPlayer(player)))
            }

            try {
                KOIN_INSTANCE.loadModules(listOf(module {
                    single<ServerSourceLine> { sourceLine }
                }))
            } catch (_: Throwable) {}
        }

        if (::audioPlayerManager.isInitialized) {
            audioPlayerManager.shutdown()
        }

        audioPlayerManager = PlasmoAudioPlayerManager()
        scope.launch {
            audioPlayerManager.registerSources()
        }
    }
}
