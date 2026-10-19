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
