package su.plo.voice.discs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import su.plo.voice.discs.event.JukeboxProgressDisplay
import java.lang.reflect.Proxy

class JukeboxProgressDisplayTest {

    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args!![0]
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Proxy<${type.simpleName}>"
                else -> call(method.name, args)
            }
        }
    )

    private fun mockWorld(name: String = "world"): World {
        return proxy(World::class.java) { methodName, _ ->
            when (methodName) {
                "getName" -> name
                else -> null
            }
        }
    }

    private fun mockBlock(x: Int, y: Int, z: Int, world: World, type: Material = Material.AIR): Block {
        val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        return proxy(Block::class.java) { methodName, args ->
            when (methodName) {
                "getX" -> x
                "getY" -> y
                "getZ" -> z
                "getWorld" -> world
                "getLocation" -> loc
                "getType" -> type
                "getRelative" -> {
                    when (args?.size) {
                        1 -> {
                            val face = args[0] as BlockFace
                            mockBlock(x + face.modX, y + face.modY, z + face.modZ, world)
                        }
                        3 -> {
                            val modX = (args[0] as Number).toInt()
                            val modY = (args[1] as Number).toInt()
                            val modZ = (args[2] as Number).toInt()
                            mockBlock(x + modX, y + modY, z + modZ, world)
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }
    }

    @Test
    fun `single jukebox without chest centers at height 1_35`() {
        val world = mockWorld()
        val jukebox = mockBlock(10, 64, 20, world, Material.JUKEBOX)

        val loc = JukeboxProgressDisplay.getDisplayLocation(jukebox)
        assertEquals(10.5, loc.x, 0.001)
        assertEquals(65.35, loc.y, 0.001)
        assertEquals(20.5, loc.z, 0.001)
    }

    @Test
    fun `double chest above jukebox centers at midpoint of both chest blocks`() {
        val world = mockWorld()
        val leftBlock = mockBlock(10, 65, 20, world, Material.CHEST)
        val rightBlock = mockBlock(11, 65, 20, world, Material.CHEST)

        val leftChest = proxy(Chest::class.java) { methodName, _ ->
            when (methodName) {
                "getBlock" -> leftBlock
                "getLocation" -> leftBlock.location
                else -> null
            }
        }

        val rightChest = proxy(Chest::class.java) { methodName, _ ->
            when (methodName) {
                "getBlock" -> rightBlock
                "getLocation" -> rightBlock.location
                else -> null
            }
        }

        var doubleChest: DoubleChest? = null
        val doubleChestInv = proxy(DoubleChestInventory::class.java) { methodName, _ ->
            when (methodName) {
                "getHolder" -> doubleChest
                "getLeftSide" -> proxy(Inventory::class.java) { name, _ ->
                    if (name == "getHolder") leftChest else null
                }
                "getRightSide" -> proxy(Inventory::class.java) { name, _ ->
                    if (name == "getHolder") rightChest else null
                }
                else -> null
            }
        }

        doubleChest = DoubleChest(doubleChestInv)

        val aboveBlock = proxy(Block::class.java) { methodName, _ ->
            when (methodName) {
                "getX" -> 10
                "getY" -> 65
                "getZ" -> 20
                "getWorld" -> world
                "getType" -> Material.CHEST
                "getState" -> proxy(Chest::class.java) { name, _ ->
                    when (name) {
                        "getBlock" -> leftBlock
                        "getInventory" -> doubleChestInv
                        else -> null
                    }
                }
                else -> null
            }
        }

        val jukebox = proxy(Block::class.java) { methodName, args ->
            when (methodName) {
                "getX" -> 10
                "getY" -> 64
                "getZ" -> 20
                "getWorld" -> world
                "getType" -> Material.JUKEBOX
                "getLocation" -> Location(world, 10.0, 64.0, 20.0)
                "getRelative" -> {
                    val modY = (args?.getOrNull(1) as? Number)?.toInt() ?: 0
                    if (modY == 1) aboveBlock else null
                }
                else -> null
            }
        }

        val loc = JukeboxProgressDisplay.getDisplayLocation(jukebox)
        // Midpoint of x=10 and x=11: (10 + 11)/2 + 0.5 = 11.0
        assertEquals(11.0, loc.x, 0.001)
        assertEquals(66.35, loc.y, 0.001)
        assertEquals(20.5, loc.z, 0.001)
    }

    @Test
    fun `hologram offset data class stores coordinates properly`() {
        val offset = su.plo.voice.discs.event.HologramOffset(1.25, -0.5, 0.75)
        assertEquals(1.25, offset.x, 0.001)
        assertEquals(-0.5, offset.y, 0.001)
        assertEquals(0.75, offset.z, 0.001)
    }
}
