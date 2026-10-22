package ca.saboor.discs.utils

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

/** Equip a real stick without discarding or hiding the player's current item. */
internal object EditingWand {
    fun equip(inventory: PlayerInventory): Boolean {
        if (inventory.itemInMainHand.type == Material.STICK) return true
        val contents = inventory.storageContents
        val stickSlot = contents.indexOfFirst { it?.type == Material.STICK }
        if (stickSlot in 0..8) {
            inventory.heldItemSlot = stickSlot
            return true
        }
        val held = inventory.itemInMainHand
        if (stickSlot >= 0) {
            val stick = inventory.getItem(stickSlot)
            inventory.setItem(stickSlot, held)
            inventory.setItemInMainHand(stick)
            return true
        }
        if (inventory.itemInOffHand.type == Material.STICK) {
            inventory.setItemInMainHand(inventory.itemInOffHand)
            inventory.setItemInOffHand(held)
            return true
        }
        if (held.type != Material.AIR) {
            val empty = contents.indexOfFirst { it == null || it.type == Material.AIR }
            if (empty < 0) return false
            inventory.setItem(empty, held)
        }
        inventory.setItemInMainHand(ItemStack(Material.STICK))
        return true
    }
}
