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
    var serverApi: VoicechatServerApi? = null
        private set

    val isAvailable: Boolean
        get() = serverApi != null

    // isConnected alone also matches synthetic connections created by VoiceBridge.
    fun hasClient(playerId: UUID): Boolean =
        serverApi?.getConnectionOf(playerId)?.isInstalled == true

    override fun getPluginId(): String = "vc-addon-discs"

    override fun initialize(api: VoicechatApi) {
        if (api is VoicechatServerApi) {
            this.serverApi = api
            registerCategory(api)
            plugin.slF4JLogger.info("Simple Voice Chat server API initialized")
        }
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { event ->
            this.serverApi = event.voicechat
            registerCategory(event.voicechat)
            plugin.slF4JLogger.info("Simple Voice Chat discs audio ready")
        }
        registration.registerEvent(VoicechatServerStoppedEvent::class.java) {
            this.serverApi = null
        }
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
