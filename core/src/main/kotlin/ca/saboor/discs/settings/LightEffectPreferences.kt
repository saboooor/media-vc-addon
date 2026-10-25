package ca.saboor.discs.settings

import ca.saboor.discs.utils.SettingsFileWriter
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** A saved choice also records that the player has answered the flashing-light warning. */
internal class LightEffectPreferences(
    private val file: File,
    private val writer: SettingsFileWriter = SettingsFileWriter.shared,
    private val onError: (Throwable) -> Unit,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val choices = mutableMapOf<UUID, Boolean>()

    init {
        if (file.exists()) runCatching {
            JsonParser.parseString(file.readText()).asJsonObject.entrySet().associate { (id, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid light-effects preference for $id" }
                UUID.fromString(id) to value.asBoolean
            }
        }.onSuccess { choices.putAll(it) }.onFailure(onError)
    }

    @Synchronized fun choice(player: UUID): Boolean? = choices[player]

    @Synchronized fun choose(player: UUID, enabled: Boolean) {
        if (choices[player] == enabled) return
        choices[player] = enabled
        val snapshot = choices.mapKeys { it.key.toString() }
        writer.submit(file, { gson.toJson(snapshot) }, onError)
    }

    @Synchronized fun migrate(player: UUID, legacyChoice: Boolean?) {
        if (player !in choices && legacyChoice != null) choose(player, legacyChoice)
    }
}
