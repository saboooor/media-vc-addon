package ca.saboor.discs.screen

import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData

/** VideoPlayer sends silently, so only competing map updates reach this listener. */
class ScreenMapPacketListener(private val screens: ScreenManager) : PacketListener {
    private val reportedConflict = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled || event.packetType != PacketType.Play.Server.MAP_DATA) return
        val mapId = WrapperPlayServerMapData(event).mapId
        if (screens.isVideoMapFor(event.user.uuid, mapId)) {
            event.isCancelled = true
            if (reportedConflict.compareAndSet(false, true)) {
                logger.info("Blocked a competing map update for active video map $mapId")
            }
        }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("ScreenMapPacketListener")
    }
}
