package ca.saboor.discs.utils

import java.lang.reflect.Proxy
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EditingWandTest {
    private class Inventory {
        val slots = arrayOfNulls<ItemStack>(36)
        var selected = 0
        var offhand = ItemStack(Material.AIR)
        val inventory = Proxy.newProxyInstance(PlayerInventory::class.java.classLoader,
            arrayOf(PlayerInventory::class.java)) { _, method, args ->
            when (method.name) {
                "getStorageContents" -> slots.copyOf()
                "getHeldItemSlot" -> selected
                "setHeldItemSlot" -> { selected = args!![0] as Int; null }
                "getItemInMainHand" -> slots[selected] ?: ItemStack(Material.AIR)
                "setItemInMainHand" -> { slots[selected] = args!![0] as ItemStack?; null }
                "getItemInOffHand" -> offhand
                "setItemInOffHand" -> { offhand = args!![0] as ItemStack; null }
                "getItem" -> slots[args!![0] as Int]
                "setItem" -> { slots[args!![0] as Int] = args[1] as ItemStack?; null }
                else -> error("Unexpected inventory call: ${method.name}")
            }
        } as PlayerInventory
    }

    @Test fun `existing hotbar stick is selected without replacing items`() {
        val state = Inventory()
        val held = ItemStack(Material.DIAMOND, 12)
        val stick = ItemStack(Material.STICK)
        state.slots[0] = held
        state.slots[4] = stick
        assertTrue(EditingWand.equip(state.inventory))
        assertEquals(4, state.selected)
        assertSame(held, state.slots[0])
        assertSame(stick, state.slots[4])
    }

    @Test fun `stick in a full inventory swaps with the held item`() {
        val state = Inventory()
        state.slots.fill(ItemStack(Material.STONE, 64))
        val held = ItemStack(Material.DIAMOND, 12)
        val stick = ItemStack(Material.STICK, 8)
        state.slots[0] = held
        state.slots[20] = stick
        assertTrue(EditingWand.equip(state.inventory))
        assertSame(stick, state.slots[0])
        assertSame(held, state.slots[20])
    }

    @Test fun `new stick preserves the held item and repeated editing reuses it`() {
        val state = Inventory()
        val held = ItemStack(Material.DIAMOND, 12)
        state.slots[0] = held
        assertTrue(EditingWand.equip(state.inventory))
        assertSame(held, state.slots[1])
        assertEquals(Material.STICK, state.slots[0]!!.type)
        val stick = state.slots[0]
        assertTrue(EditingWand.equip(state.inventory))
        assertSame(stick, state.slots[0])
        assertEquals(2, state.slots.count { it != null })
    }

    @Test fun `full inventory without a stick stays unchanged`() {
        val state = Inventory()
        state.slots.fill(ItemStack(Material.STONE, 64))
        val before = state.slots.copyOf()
        assertFalse(EditingWand.equip(state.inventory))
        before.indices.forEach { assertSame(before[it], state.slots[it]) }
    }

    @Test fun `offhand stick swaps without needing a free slot`() {
        val state = Inventory()
        state.slots.fill(ItemStack(Material.STONE, 64))
        val held = state.slots[0]
        val stick = ItemStack(Material.STICK)
        state.offhand = stick
        assertTrue(EditingWand.equip(state.inventory))
        assertSame(stick, state.slots[0])
        assertSame(held, state.offhand)
    }
}
