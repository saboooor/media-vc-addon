package ca.saboor.discs.utils

import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenAudienceReadinessTest {
    private fun player(id: UUID): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getUniqueId" -> id
            "getLocation" -> Location(null, 0.0, 64.0, 0.0)
            else -> null
        }
    } as Player

    @Test fun `screen packets wait for client movement and reject stale sessions`() {
        PlayerAudience.shutdown()
        val id = UUID.randomUUID()
        val first = player(id)
        val from = Location(null, 0.0, 64.0, 0.0)
        val to = from.clone().apply { yaw = 90f }
        try {
            PlayerAudience.onJoin(PlayerJoinEvent(first, null as String?))
            assertFalse(PlayerAudience.canReceiveScreen(first))
            PlayerAudience.onMove(PlayerTeleportEvent(first, from, to))
            assertFalse(PlayerAudience.canReceiveScreen(first))
            PlayerAudience.onMove(PlayerMoveEvent(first, from, to))
            assertTrue(PlayerAudience.canReceiveScreen(first))
            PlayerAudience.onQuit(PlayerQuitEvent(first, null as String?))
            assertFalse(PlayerAudience.canReceiveScreen(first))
            val second = player(id)
            PlayerAudience.onJoin(PlayerJoinEvent(second, null as String?))
            assertFalse(PlayerAudience.canReceiveScreen(second))
            PlayerAudience.onMove(PlayerMoveEvent(second, from, to))
            assertTrue(PlayerAudience.canReceiveScreen(second))
            assertFalse(PlayerAudience.canReceiveScreen(first))
        } finally {
            PlayerAudience.shutdown()
        }
    }
}
