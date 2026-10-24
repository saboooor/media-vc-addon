package ca.saboor.discs.utils

import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerAudienceTest {
    @AfterEach fun clear() { PlayerAudience.shutdown() }
    private fun world(name: String, id: UUID): World = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(World::class.java)) { self, method, args ->
        when (method.name) {
            "getName" -> name
            "getUID" -> id
            "equals" -> self === args[0]
            "hashCode" -> System.identityHashCode(self)
            "toString" -> name
            else -> null
        }
    } as World
    private fun player(location: Location, reads: AtomicInteger, id: UUID = UUID.randomUUID()): Player =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { self, method, args ->
            when (method.name) {
                "getLocation" -> { reads.incrementAndGet(); location }
                "getUniqueId" -> id
                "equals" -> self === args[0]
                "hashCode" -> System.identityHashCode(self)
                "toString" -> id.toString()
                else -> null
            }
        } as Player

    @Test fun `audience queries use captured positions and respect worlds range and teleports`() {
        val worldId = UUID.randomUUID()
        val first = world("first", worldId)
        val second = world("second", UUID.randomUUID())
        val reads = AtomicInteger()
        val location = Location(first, 3.0, 4.0, 0.0)
        val viewer = player(location, reads)
        PlayerAudience.onJoin(PlayerJoinEvent(viewer, Component.empty()))
        repeat(10) {
            assertEquals(listOf(viewer), PlayerAudience.nearby("first", 0.0, 0.0, 0.0, 5.0))
            assertTrue(PlayerAudience.hasNearby(worldId.toString(), 0.0, 0.0, 0.0, 5.0))
            assertFalse(PlayerAudience.hasNearby("second", 0.0, 0.0, 0.0, 100.0))
            assertFalse(PlayerAudience.hasNearby("first", 0.0, 0.0, 0.0, 4.9))
        }
        assertEquals(1, reads.get(), "Background audience queries must never read live player locations")
        val destination = Location(second, 1.0, 0.0, 0.0)
        PlayerAudience.onTeleport(PlayerTeleportEvent(viewer, location, destination, PlayerTeleportEvent.TeleportCause.PLUGIN))
        assertFalse(PlayerAudience.hasNearby("first", 0.0, 0.0, 0.0, 100.0))
        assertTrue(PlayerAudience.hasNearby("second", 0.0, 0.0, 0.0, 5.0))
        PlayerAudience.onQuit(PlayerQuitEvent(viewer, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
        assertFalse(PlayerAudience.hasNearby("second", 0.0, 0.0, 0.0, 5.0))
        assertEquals(1, reads.get())
    }

    @Test fun `rotation alone preserves the snapshot and reconnect replaces the player instance`() {
        val world = world("world", UUID.randomUUID())
        val location = Location(world, 0.0, 0.0, 0.0)
        val id = UUID.randomUUID()
        val reads = AtomicInteger()
        val original = player(location, reads, id)
        PlayerAudience.onJoin(PlayerJoinEvent(original, Component.empty()))
        val before = PlayerAudience.positions("world", 0.0, 0.0, 0.0, 10.0).single()
        PlayerAudience.onMove(PlayerMoveEvent(original, location, location.clone().apply { yaw = 90f }))
        assertSame(before, PlayerAudience.positions("world", 0.0, 0.0, 0.0, 10.0).single())
        PlayerAudience.onQuit(PlayerQuitEvent(original, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
        val replacement = player(location, reads, id)
        PlayerAudience.onJoin(PlayerJoinEvent(replacement, Component.empty()))
        assertSame(replacement, PlayerAudience.nearby("world", 0.0, 0.0, 0.0, 10.0).single())
    }
}
