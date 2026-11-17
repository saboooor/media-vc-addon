package ca.saboor.discs.screen

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** Only negotiated clients receive RGB. No media URL or authentication token leaves the server. */
object CompanionScreens : Listener, PluginMessageListener {
    const val PROTOCOL = 5
    private const val MAX_SCREEN_MAPS = 32 * 32
    internal fun supportsProtocol(message: ByteArray) = message.contentEquals(byteArrayOf(PROTOCOL.toByte()))
    const val HELLO = "vcb:screen_hello"
    const val FRAME = "vcb:screen_rgb"
    const val METADATA = "vcb:screen_metadata"
    const val CLEAR = "vcb:screen_clear"
    private val clients = ConcurrentHashMap.newKeySet<UUID>()
    private val metadataSent = ConcurrentHashMap<UUID, MutableSet<Int>>()
    fun start(plugin: JavaPlugin) {
        pluginInstance = plugin
        plugin.server.messenger.registerIncomingPluginChannel(plugin, HELLO, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, FRAME)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, METADATA)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CLEAR)
        plugin.server.pluginManager.registerEvents(this, plugin)
    }
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel == HELLO && supportsProtocol(message)) {
            clients.add(player.uniqueId)
        }
    }
    fun capable(player: Player) = clients.contains(player.uniqueId)
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    fun frame(screen: Screen, jpeg: ByteArray, players: List<Player>, codec: Int = 0, width: Int = 0, height: Int = 0, stream: Int = 0, frame: Int = 0, keyframe: Boolean = true) {
        require(jpeg.size <= 4 * 1024 * 1024)
        val ids = screen.flatMapIds
        require(ids.size == screen.columns * screen.rows && ids.size in 1..MAX_SCREEN_MAPS)
        val viewers = players.filter(::capable)
        if (viewers.isEmpty()) return
        val anchor = ids.first()
        // Screen geometry is sent once per viewer; frame packets contain only the video.
        viewers.forEach { viewer ->
            val sent = metadataSent.computeIfAbsent(viewer.uniqueId) { ConcurrentHashMap.newKeySet() }
            if (sent.add(anchor)) sendMetadata(screen, viewer)
        }
        val body = ByteBuffer.allocate(36 + jpeg.size)
            .putInt(screen.columns).putInt(screen.rows)
            .putInt(codec).putInt(width).putInt(height).putInt(stream).putInt(frame).putInt(if (keyframe) 1 else 0).putInt(0)
        body.put(jpeg)
        val bytes = body.array()
        val number = sequence.incrementAndGet()
        for (offset in bytes.indices step 32748) {
            val length = minOf(32748, bytes.size - offset)
            val chunk = ByteBuffer.allocate(16 + length).putInt(anchor).putInt(number)
                .putInt(bytes.size).putInt(offset).put(bytes, offset, length).array()
            val packet = WrapperPlayServerPluginMessage(FRAME, chunk)
            viewers.forEach { PacketEvents.getAPI().playerManager.sendPacketSilently(it, packet) }
        }
    }
    fun clear(mapId: Int) {
        metadataSent.values.forEach { it.remove(mapId) }
        val packet = WrapperPlayServerPluginMessage(CLEAR, ByteBuffer.allocate(4).putInt(mapId).array())
        clients.forEach { id ->
            Bukkit.getPlayer(id)?.let { PacketEvents.getAPI().playerManager.sendPacketSilently(it, packet) }
        }
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        clients.remove(event.player.uniqueId)
        metadataSent.remove(event.player.uniqueId)
    }
    fun shutdown() {
        metadataSent.clear()
        clients.clear()
        pluginInstance = null
    }

    private fun sendMetadata(screen: Screen, player: Player) {
        val ids = screen.flatMapIds
        val body = ByteBuffer.allocate(16 + ids.size * 4)
            .putInt(ids.first()).putInt(screen.columns).putInt(screen.rows).putInt(ids.size)
        ids.forEach { body.putInt(it) }
        player.sendPluginMessage(pluginInstance ?: return, METADATA, body.array())
    }

    @Volatile private var pluginInstance: JavaPlugin? = null
}
