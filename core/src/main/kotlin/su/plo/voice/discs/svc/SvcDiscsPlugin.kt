package su.plo.voice.discs.svc

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class SvcDiscsPlugin(private val plugin: JavaPlugin) : VoicechatPlugin {

    @Volatile
    private var active = true

    @Volatile
    var serverApi: VoicechatServerApi? = null
        private set

    val isAvailable: Boolean
        get() = serverApi != null

    // isConnected alone also matches synthetic connections created by VoiceBridge.
    fun hasClient(playerId: UUID): Boolean =
        serverApi?.getConnectionOf(playerId)?.isInstalled == true

    override fun getPluginId(): String = "vc-addon-discs"

    override fun initialize(api: VoicechatApi) {
        if (!active) return
        if (api is VoicechatServerApi) {
            this.serverApi = api
            registerCategory(api)
            plugin.slF4JLogger.info("Simple Voice Chat server API initialized")
        }
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { event ->
            if (!active) return@registerEvent
            this.serverApi = event.voicechat
            registerCategory(event.voicechat)
            plugin.slF4JLogger.info("Simple Voice Chat discs audio ready")
        }
        registration.registerEvent(VoicechatServerStoppedEvent::class.java) {
            this.serverApi = null
        }
    }

    /**
     * PlugMan reloads this plugin without rebuilding Simple Voice Chat's plugin registry.
     * Keep the registered addon instance and temporarily disable its API access instead of
     * registering another plugin with the same ID.
     */
    fun suspend() {
        active = false
        serverApi = null
    }

    fun resume() {
        active = true
    }

    /** SVC only initializes registered addons at startup, so late loads need its existing API. */
    fun attachToRunningServer() {
        if (!active || serverApi != null) return
        try {
            val voicechat = plugin.server.pluginManager.getPlugin("voicechat") ?: return
            if (!voicechat.isEnabled) return
            val implementation = voicechat.javaClass.classLoader.loadClass(
                "de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl"
            )
            initialize(implementation.getMethod("instance").invoke(null) as VoicechatServerApi)
        } catch (e: Exception) {
            plugin.slF4JLogger.warn("Unable to attach to the running Simple Voice Chat API; restart the server", e)
        }
    }

    fun close() {
        active = false
        serverApi = null
    }

    private fun registerCategory(api: VoicechatServerApi) {
        try {
            val category = api.volumeCategoryBuilder()
                .setId("discs")
                .setName("Music Discs")
                .setDescription("Volume for custom music discs and goat horns")
                .build()
            api.registerVolumeCategory(category)
        } catch (e: Exception) {
            plugin.slF4JLogger.warn("Failed to register discs volume category", e)
        }
    }

    fun createLocationalChannel(location: Location, distance: Float): LocationalAudioChannel? {
        val api = serverApi ?: return null
        val channelId = UUID.randomUUID()
        val world = location.world ?: return null
        val channel = api.createLocationalAudioChannel(
            channelId,
            api.fromServerLevel(world),
            api.createPosition(location.x, location.y, location.z)
        ) ?: return null
        channel.distance = distance
        try {
            channel.setCategory("discs")
        } catch (_: Throwable) {}
        return channel
    }

    fun createEntityChannel(player: Player, distance: Float): EntityAudioChannel? {
        val api = serverApi ?: return null
        val channelId = UUID.randomUUID()
        val channel = api.createEntityAudioChannel(
            channelId,
            api.fromEntity(player)
        ) ?: return null
        channel.distance = distance
        try {
            channel.setCategory("discs")
        } catch (_: Throwable) {}
        return channel
    }

}
