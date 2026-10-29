package ca.saboor.discs

import ca.saboor.discs.settings.LightEffectPreferences
import ca.saboor.discs.utils.SettingsFileWriter
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LightEffectPreferencesTest {
    @TempDir lateinit var directory: Path

    @Test fun `show and hide choices survive restart while new players have no choice`() {
        val file = directory.resolve("light_effect_preferences.json").toFile()
        val showing = UUID.randomUUID()
        val hiding = UUID.randomUUID()
        SettingsFileWriter(60_000).use { writer ->
            val original = LightEffectPreferences(file, writer) { throw AssertionError(it) }
            original.choose(showing, true)
            original.choose(hiding, false)
            writer.flush()
            val restarted = LightEffectPreferences(file, writer) { throw AssertionError(it) }
            assertEquals(true, restarted.choice(showing))
            assertEquals(false, restarted.choice(hiding))
            assertNull(restarted.choice(UUID.randomUUID()))
        }
    }

    @Test fun `warning is remembered across restarts and preserves explicit choices`() {
        val file = directory.resolve("preferences.json").toFile()
        val player = UUID.randomUUID()
        SettingsFileWriter(60_000).use { writer ->
            val preferences = LightEffectPreferences(file, writer) { throw AssertionError(it) }
            preferences.markWarningShown(player)
            preferences.markWarningShown(player)
            writer.flush()
            val restarted = LightEffectPreferences(file, writer) { throw AssertionError(it) }
            assertEquals(false, restarted.choice(player))
            restarted.choose(player, true)
            restarted.markWarningShown(player)
            assertEquals(true, restarted.choice(player))
        }
    }

    @Test fun `legacy preferences migrate once without overwriting file choices`() {
        val file = directory.resolve("preferences.json").toFile()
        val player = UUID.randomUUID()
        val unchosen = UUID.randomUUID()
        SettingsFileWriter(60_000).use { writer ->
            val preferences = LightEffectPreferences(file, writer) { throw AssertionError(it) }
            preferences.migrate(player, true)
            preferences.choose(player, false)
            preferences.migrate(player, true)
            preferences.migrate(unchosen, null)
            writer.flush()
            val restarted = LightEffectPreferences(file, writer) { throw AssertionError(it) }
            assertEquals(false, restarted.choice(player))
            assertNull(restarted.choice(unchosen))
        }
    }
}
