package su.plo.voice.discs.screen

import org.bukkit.Material
import org.bukkit.entity.ItemFrame
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.koin.core.component.inject
import su.plo.voice.discs.utils.PluginKoinComponent

class ScreenEventListener : Listener, PluginKoinComponent {

    private val screenManager: ScreenManager by inject()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val entity = event.rightClicked
        if (entity !is ItemFrame) return

        val player = event.player
        val session = screenManager.getSelectionSession(player.uniqueId)

        if (session != null) {
            val handled = session.handleItemFrameClick(entity, player)
            if (handled) {
                event.isCancelled = true
                return
            }
        }

        // Prevent rotating maps in registered active screen item frames
        val screen = screenManager.getScreenForFrame(entity.uniqueId)
        if (screen != null && screen.hasLinkedJukebox()) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val player = event.player

        val session = screenManager.getSelectionSession(player.uniqueId) ?: return
        if (session.state == ScreenSelectionSession.State.WAITING_FOR_JUKEBOX) {
            if (block.type == Material.JUKEBOX) {
                val handled = session.handleJukeboxClick(block, player)
                if (handled) {
                    event.isCancelled = true
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        val entity = event.entity
        if (entity is ItemFrame) {
            val screen = screenManager.getScreenForFrame(entity.uniqueId)
            if (screen != null && screen.hasLinkedJukebox()) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreakByEntity(event: HangingBreakByEntityEvent) {
        val entity = event.entity
        if (entity is ItemFrame) {
            val screen = screenManager.getScreenForFrame(entity.uniqueId)
            if (screen != null && screen.hasLinkedJukebox()) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        if (entity is ItemFrame) {
            val screen = screenManager.getScreenForFrame(entity.uniqueId)
            if (screen != null && screen.hasLinkedJukebox()) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        screenManager.cancelSelection(event.player)
    }
}

