package ca.saboor.discs.utils

import ca.saboor.discs.utils.extend.suspendSyncFor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.plugin.java.JavaPlugin
import su.plo.voice.api.server.PlasmoVoiceServer

/** Position snapshots are captured on the player's owning thread and queried without Bukkit calls. */
object PlayerAudience : Listener {
    data class Viewer(val player: Player, val world: String, val worldId: String, val x: Double, val y: Double,
                      val z: Double, val updated: Long) {
        fun within(x: Double, y: Double, z: Double, range: Double): Boolean {
            val dx = this.x - x; val dy = this.y - y; val dz = this.z - z
            return dx * dx + dy * dy + dz * dz <= range * range
        }
    }
    private val entries = ConcurrentHashMap<UUID, Viewer>()
    fun start(plugin: JavaPlugin, scope: CoroutineScope, voiceServer: PlasmoVoiceServer?) {
        entries.clear()
        val players = Bukkit.getOnlinePlayers().toList()
        scope.launch {
            for (player in players) suspendSyncFor(player, voiceServer, plugin) {
                if (player.isOnline) update(player, player.location)
            }
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
    }
    private fun update(player: Player, location: Location) {
        entries[player.uniqueId] = Viewer(player, location.world?.name ?: return, location.world!!.uid.toString(),
            location.x, location.y, location.z, System.nanoTime())
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (event !is PlayerTeleportEvent && from.world == to.world && from.x == to.x && from.y == to.y && from.z == to.z) return
        if (event is PlayerTeleportEvent || System.nanoTime() - (entries[event.player.uniqueId]?.updated ?: 0L) >= 100_000_000L)
            update(event.player, to)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) { event.to?.let { update(event.player, it) } }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) { update(event.player, event.respawnLocation) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleMove(event: VehicleMoveEvent) {
        fun samplePassengers(entity: org.bukkit.entity.Entity) {
            for (passenger in entity.passengers) {
                if (passenger is Player && System.nanoTime() - (entries[passenger.uniqueId]?.updated ?: 0L) >= 100_000_000L)
                    update(passenger, passenger.location)
                samplePassengers(passenger)
            }
        }
        samplePassengers(event.vehicle)
    }
    @EventHandler fun onJoin(event: PlayerJoinEvent) { update(event.player, event.player.location) }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) { update(event.player, event.player.location) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { entries.remove(event.player.uniqueId) }
    fun positions(world: String, x: Double, y: Double, z: Double, range: Double): List<Viewer> =
        entries.values.filter { (it.world == world || it.worldId == world) && it.within(x, y, z, range) }
    fun nearby(world: String, x: Double, y: Double, z: Double, range: Double): List<Player> =
        positions(world, x, y, z, range).map { it.player }
    fun hasNearby(world: String, x: Double, y: Double, z: Double, range: Double): Boolean =
        entries.values.any { inRange(it, world, x, y, z, range * range) }
    private fun inRange(entry: Viewer, world: String, x: Double, y: Double, z: Double, squared: Double): Boolean {
        if (entry.world != world && entry.worldId != world) return false
        val dx = entry.x - x; val dy = entry.y - y; val dz = entry.z - z
        return dx * dx + dy * dy + dz * dz <= squared
    }
    fun shutdown() { entries.clear() }
}
