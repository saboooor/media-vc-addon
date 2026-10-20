package su.plo.voice.discs.utils.extend

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import su.plo.voice.discs.AddonConfig
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.item.DiscHelper

fun ItemStack.burnMetadata(identifier: String, name: String, config: AddonConfig, discHelper: DiscHelper) {
    val item = this
    editMeta { meta ->
        meta.addItemFlags(*ItemFlag.values())

        meta.persistentDataContainer.set(
            AddonKeys.identifierKey,
            PersistentDataType.STRING,
            identifier
        )

        if (config.addGlintToCustomDiscs) {
            meta.forbidGrindstone()
            meta.addEnchant(Enchantment.MENDING, 1, false)
        }

        val loreName = Component.text(name, NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)

        when (config.burnLoreMethod) {
            AddonConfig.LoreMethod.REPLACE -> {
                meta.lore(listOf(loreName))
            }

            AddonConfig.LoreMethod.APPEND -> {
                val currentLore = meta.lore()?.let {
                    if (item.isCustomDisc(discHelper)) {
                        it.dropLast(1)
                    } else {
                        it
                    }
                } ?: emptyList()
                meta.lore(currentLore + listOf(loreName))
            }

            AddonConfig.LoreMethod.DISABLE -> {} // do nothing
        }
    }
}

fun String.cleanArtistName(): String =
    replace(Regex("(?i)\\s*-\\s*topic$"), "")
        .replace(Regex("(?i)\\s*[-–—]?\\s*official(?:\\s*channel)?$"), "")
        .replace(Regex("(?i)vevo$"), "")
        .replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " ")
        .trim()
        .takeUnless { it.equals("unknown artist", ignoreCase = true) || it.equals("unknown", ignoreCase = true) }
        .orEmpty()

fun String.cleanTrackName(): String =
    replace(Regex("(?i)\\s*-\\s*topic\\b"), "").trim()

fun String.containsArtist(artist: String): Boolean {
    val clean = artist.cleanArtistName()
    if (clean.isBlank() || this.isBlank()) return false
    val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(clean)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    if (pattern.containsMatchIn(this)) return true

    val noSpacesArtist = clean.replace(Regex("\\s+"), "")
    if (noSpacesArtist.length >= 3) {
        val patternNoSpaces = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(noSpacesArtist)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        if (patternNoSpaces.containsMatchIn(this)) return true
    }

    return false
}

fun formatTrackDisplay(trackTitle: String, artistName: String): String {
    val cleanTitle = trackTitle.cleanTrackName()
    val cleanArtist = artistName.cleanArtistName()
    return if (cleanArtist.isNotBlank() && !cleanTitle.containsArtist(cleanArtist)) {
        "$cleanArtist - $cleanTitle"
    } else {
        cleanTitle
    }
}

/** Remove the custom song without consuming the physical disc. */
fun ItemStack.eraseBurnMetadata(config: AddonConfig, discHelper: DiscHelper) {
    editMeta { meta ->
        meta.removeItemFlags(*ItemFlag.values())
        meta.persistentDataContainer.remove(AddonKeys.identifierKey)
        meta.persistentDataContainer.remove(AddonKeys.oldIdentifierKey)
        if (config.addGlintToCustomDiscs) {
            meta.allowGrindstone()
            meta.removeEnchant(Enchantment.MENDING)
        }
        when (config.burnLoreMethod) {
            AddonConfig.LoreMethod.REPLACE -> meta.lore(null)
            AddonConfig.LoreMethod.APPEND -> meta.lore(meta.lore()?.dropLast(1)?.takeIf { it.isNotEmpty() })
            AddonConfig.LoreMethod.DISABLE -> {}
        }
    }
    if (org.bukkit.Bukkit.getServer().getMinecraftVersionInt() >= 12103) discHelper.showSongTooltip(this, true)
}
