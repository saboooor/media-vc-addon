package ca.saboor.discs

import ca.saboor.discs.visualizer.BeaconShutter
import ca.saboor.discs.visualizer.LightBlock
import java.lang.reflect.Proxy
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BeaconShutterTest {
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                else -> call(method.name, args)
            }
        })
    private fun data(material: Material): BlockData {
        lateinit var value: BlockData
        value = proxy(BlockData::class.java) { name, _ -> when (name) {
            "getMaterial" -> material
            "clone" -> value
            else -> null
        } }
        return value
    }
    private inner class Fixture {
        val empty = data(Material.AIR)
        val closed = data(Material.BLACK_CONCRETE)
        val beaconData = data(Material.BEACON)
        var topData = empty
        var beaconWrites = 0
        val world = proxy(World::class.java) { name, _ -> if (name == "getMaxHeight") 320 else null }
        val top = proxy(Block::class.java) { name, args -> when (name) {
            "getType" -> topData.material
            "getBlockData" -> topData
            "setBlockData" -> { topData = args!![0] as BlockData; null }
            else -> null
        } }
        val beacon = proxy(Block::class.java) { name, _ -> when (name) {
            "getY" -> 64
            "getWorld" -> world
            "getRelative" -> top
            "getType" -> Material.BEACON
            "getBlockData" -> beaconData
            "setBlockData" -> { beaconWrites++; null }
            else -> null
        } }
    }

    @Test fun `beam shutter closes and reopens without replacing the beacon`() {
        val f = Fixture()
        assertSame(f.empty, BeaconShutter.capture(f.beacon))
        BeaconShutter.update(f.beacon, f.empty, f.closed, false)
        assertSame(f.closed, f.topData)
        BeaconShutter.update(f.beacon, f.empty, f.closed, true)
        assertSame(f.empty, f.topData)
        LightBlock.update(f.beacon, f.beaconData, false)
        assertEquals(0, f.beaconWrites)
    }

    @Test fun `occupied shutter space is neither captured nor overwritten`() {
        val f = Fixture()
        val glass = data(Material.GLASS)
        f.topData = glass
        assertNull(BeaconShutter.capture(f.beacon))
        BeaconShutter.update(f.beacon, f.empty, f.closed, false)
        BeaconShutter.update(f.beacon, f.empty, f.closed, true)
        assertSame(glass, f.topData)
        assertEquals(0, f.beaconWrites)
    }
}
