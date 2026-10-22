package ca.saboor.discs.bridge

import java.util.UUID
import org.bukkit.Bukkit
import su.plo.voice.api.server.PlasmoVoiceServer

object VoiceBridgeCompat {

    fun isPlayerListeningOnPv(playerUuid: UUID, voiceServer: PlasmoVoiceServer?): Boolean {
        val bridge = Bukkit.getPluginManager().getPlugin("voice-bridge")?.takeIf { it.isEnabled }
        if (bridge != null) {
            try {
                // Kotlin's companion instance getter is not a static getInstance method.
                // Use the Bukkit plugin instance and its public session API instead.
                val manager = bridge.javaClass.getMethod("getSessionManager").invoke(bridge)
                val session = manager.javaClass.getMethod("getSession", UUID::class.java)
                    .invoke(manager, playerUuid)
                if (session != null) {
                    val hasModType = session.javaClass.methods.first {
                        it.name == "hasModType" && it.parameterCount == 1
                    }
                    val pvModType = hasModType.parameterTypes[0].enumConstants.first {
                        (it as Enum<*>).name == "PLASMO_VOICE"
                    }
                    return hasModType.invoke(session, pvModType) as Boolean
                }
            } catch (_: ReflectiveOperationException) {
                // An older bridge may not expose the session API.
            }
        }

        return voiceServer?.playerManager?.getPlayerById(playerUuid)
            ?.orElse(null)?.hasVoiceChat() == true
    }

}
