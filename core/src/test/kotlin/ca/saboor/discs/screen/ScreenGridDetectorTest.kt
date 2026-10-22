package ca.saboor.discs.screen

import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenGridDetectorTest {

    private fun mockBlock(x: Int, y: Int, z: Int, world: World): Block {
        return proxy(Block::class.java) { name, _ ->
            when (name) {
                "getX" -> x
                "getY" -> y
                "getZ" -> z
                "getWorld" -> world
                else -> null
            }
        }
    }

    private fun mockItemFrame(x: Int, y: Int, z: Int, facing: BlockFace, world: World): ItemFrame {
        val block = mockBlock(x, y, z, world)
        val loc = Location(world, x + 0.5, y + 0.5, z + 0.5)
        val uuid = UUID.nameUUIDFromBytes("$x:$y:$z".toByteArray())
        return proxy(ItemFrame::class.java) { name, _ ->
            when (name) {
                "getLocation" -> loc
                "getWorld" -> world
                "getFacing" -> facing
                "getUniqueId" -> uuid
                else -> null
            }
        }
    }

    private fun mockWorld(name: String, framesSupplier: () -> List<ItemFrame>): World {
        return proxy(World::class.java) { methodName, args ->
            when (methodName) {
                "getName" -> name
                "getNearbyEntities" -> framesSupplier()
                else -> null
            }
        }
    }

    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T {
        return type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "equals" -> instance === args!![0]
                    "hashCode" -> System.identityHashCode(instance)
                    else -> handler(method.name, args)
                }
            }
        )
    }

    @Test
    fun `detects 3x2 south facing screen in correct order`() {
        var worldRef: World? = null
        val allFrames = mutableListOf<ItemFrame>()
        val world = mockWorld("world") { allFrames }
        worldRef = world

        // Build 3 columns (x: 10..12) and 2 rows (y: 64..65) at z=5 facing SOUTH
        for (x in 10..12) {
            for (y in 64..65) {
                allFrames.add(mockItemFrame(x, y, 5, BlockFace.SOUTH, world))
            }
        }

        val corner1 = allFrames.first { it.location.blockX == 10 && it.location.blockY == 65 }
        val corner2 = allFrames.first { it.location.blockX == 12 && it.location.blockY == 64 }

        val result = ScreenGridDetector.detectGrid(corner1, corner2)

        assertEquals(3, result.columns)
        assertEquals(2, result.rows)
        assertEquals(BlockFace.SOUTH, result.facing)

        // Top row should have y=65, columns 10, 11, 12
        assertEquals(10, result.orderedFrames[0][0].location.blockX)
        assertEquals(65, result.orderedFrames[0][0].location.blockY)
        assertEquals(11, result.orderedFrames[0][1].location.blockX)
        assertEquals(65, result.orderedFrames[0][1].location.blockY)
        assertEquals(12, result.orderedFrames[0][2].location.blockX)
        assertEquals(65, result.orderedFrames[0][2].location.blockY)

        // Bottom row should have y=64, columns 10, 11, 12
        assertEquals(10, result.orderedFrames[1][0].location.blockX)
        assertEquals(64, result.orderedFrames[1][0].location.blockY)
        assertEquals(11, result.orderedFrames[1][1].location.blockX)
        assertEquals(64, result.orderedFrames[1][1].location.blockY)
        assertEquals(12, result.orderedFrames[1][2].location.blockX)
        assertEquals(64, result.orderedFrames[1][2].location.blockY)
    }

    @Test
    fun `detects 2x2 north facing screen in correct order`() {
        val allFrames = mutableListOf<ItemFrame>()
        val world = mockWorld("world") { allFrames }

        // Build 2 columns (x: 20..21) and 2 rows (y: 70..71) at z=10 facing NORTH
        for (x in 20..21) {
            for (y in 70..71) {
                allFrames.add(mockItemFrame(x, y, 10, BlockFace.NORTH, world))
            }
        }

        val corner1 = allFrames.first { it.location.blockX == 20 && it.location.blockY == 71 }
        val corner2 = allFrames.first { it.location.blockX == 21 && it.location.blockY == 70 }

        val result = ScreenGridDetector.detectGrid(corner1, corner2)

        assertEquals(2, result.columns)
        assertEquals(2, result.rows)

        // For NORTH facing wall, looking south: left is +X (21), right is -X (20)
        assertEquals(21, result.orderedFrames[0][0].location.blockX)
        assertEquals(20, result.orderedFrames[0][1].location.blockX)
    }

    @Test
    fun `fails when item frames are in different worlds`() {
        val world1 = mockWorld("world_1") { emptyList() }
        val world2 = mockWorld("world_2") { emptyList() }

        val frame1 = mockItemFrame(0, 64, 0, BlockFace.NORTH, world1)
        val frame2 = mockItemFrame(1, 65, 0, BlockFace.NORTH, world2)

        assertThrows(ScreenGridDetector.DetectionException::class.java) {
            ScreenGridDetector.detectGrid(frame1, frame2)
        }
    }

    @Test
    fun `fails when item frames have different facing directions`() {
        val world = mockWorld("world") { emptyList() }

        val frame1 = mockItemFrame(0, 64, 0, BlockFace.NORTH, world)
        val frame2 = mockItemFrame(1, 65, 0, BlockFace.SOUTH, world)

        assertThrows(ScreenGridDetector.DetectionException::class.java) {
            ScreenGridDetector.detectGrid(frame1, frame2)
        }
    }
}

