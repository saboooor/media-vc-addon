package su.plo.voice.discs.screen

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only negotiated clients receive RGB. No media URL or authentication token leaves the server. */
object CompanionScreens : Listener, PluginMessageListener {
    const val HELLO = "vcb:screen_hello"
    const val FRAME = "vcb:screen_rgb"
    const val CLEAR = "vcb:screen_clear"
    private val clients = ConcurrentHashMap<UUID, Player>()
    private val maps = ConcurrentHashMap.newKeySet<Int>()
    fun start(plugin: JavaPlugin) {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, HELLO, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, FRAME)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CLEAR)
        plugin.server.pluginManager.registerEvents(this, plugin)
    }
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel == HELLO && message.contentEquals(byteArrayOf(1))) clients[player.uniqueId] = player
    }
    fun capable(player: Player) = clients.containsKey(player.uniqueId)
    fun frame(mapId: Int, jpeg: ByteArray, players: List<Player>) {
        require(jpeg.size <= 32760)
        maps.add(mapId)
        val bytes = ByteBuffer.allocate(4 + jpeg.size).putInt(mapId).put(jpeg).array()
        val packet = WrapperPlayServerPluginMessage(FRAME, bytes)
        players.filter(::capable).forEach { PacketEvents.getAPI().playerManager.sendPacketSilently(it, packet) }
    }
    fun clear(mapId: Int) {
        maps.remove(mapId)
        val packet = WrapperPlayServerPluginMessage(CLEAR, ByteBuffer.allocate(4).putInt(mapId).array())
        clients.values.forEach { PacketEvents.getAPI().playerManager.sendPacketSilently(it, packet) }
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { clients.remove(event.player.uniqueId) }
    fun shutdown() {
        maps.toList().forEach(::clear)
        clients.clear()
    }
}
