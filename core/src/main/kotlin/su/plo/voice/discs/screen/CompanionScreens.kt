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
        if (channel == HELLO && message.contentEquals(byteArrayOf(2))) clients[player.uniqueId] = player
    }
    fun capable(player: Player) = clients.containsKey(player.uniqueId)
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    fun frame(screen: Screen, jpeg: ByteArray, players: List<Player>) {
        require(jpeg.size <= 4 * 1024 * 1024)
        val ids = screen.mapIds.flatten()
        require(ids.size == screen.columns * screen.rows && ids.size in 1..256)
        val anchor = ids.first()
        maps.add(anchor)
        // One JPEG for the entire display. Chunks are transport fragments, never render tiles.
        val body = ByteBuffer.allocate(8 + ids.size * 4 + jpeg.size)
            .putInt(screen.columns).putInt(screen.rows)
        ids.forEach { body.putInt(it) }
        body.put(jpeg)
        val bytes = body.array()
        val number = sequence.incrementAndGet()
        val viewers = players.filter(::capable)
        for (offset in bytes.indices step 32748) {
            val length = minOf(32748, bytes.size - offset)
            val chunk = ByteBuffer.allocate(16 + length).putInt(anchor).putInt(number)
                .putInt(bytes.size).putInt(offset).put(bytes, offset, length).array()
            val packet = WrapperPlayServerPluginMessage(FRAME, chunk)
            viewers.forEach { PacketEvents.getAPI().playerManager.sendPacketSilently(it, packet) }
        }
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
