package ca.saboor.discs

import ca.saboor.discs.visualizer.LightBlock
import java.lang.reflect.Proxy
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Lightable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LightBlockTest {
    private fun data(material: Material): BlockData = Proxy.newProxyInstance(
        BlockData::class.java.classLoader, arrayOf(BlockData::class.java)
    ) { self, method, args -> when (method.name) {
        "getMaterial" -> material
        "clone" -> self
        "equals" -> self === args!![0]
        "hashCode" -> System.identityHashCode(self)
        else -> null
    } } as BlockData

    private class Fixture(var data: BlockData) {
        var writes = 0
        val block = Proxy.newProxyInstance(Block::class.java.classLoader, arrayOf(Block::class.java)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                "getType" -> data.material
                "getBlockData" -> data
                "setBlockData" -> { data = args!![0] as BlockData; writes++; null }
                else -> null
            }
        } as Block
    }

    @Test fun `a darkened glowstone restores its original data on release`() {
        val original = data(Material.GLOWSTONE)
        val fixture = Fixture(data(Material.BLACK_CONCRETE))
        LightBlock.restore(fixture.block, original)
        assertSame(original, fixture.data)
        assertEquals(1, fixture.writes)
        LightBlock.restore(fixture.block, original)
        assertEquals(1, fixture.writes)
    }

    @Test fun `lighting an off block restores the original material`() {
        val original = data(Material.SHROOMLIGHT)
        val fixture = Fixture(data(Material.BLACK_CONCRETE))
        LightBlock.update(fixture.block, original, true)
        assertSame(original, fixture.data)
    }

    @Test fun `replaced blocks are not overwritten by playback or cleanup`() {
        val fixture = Fixture(data(Material.DIAMOND_BLOCK))
        val original = data(Material.GLOWSTONE)
        LightBlock.update(fixture.block, original, true)
        LightBlock.restore(fixture.block, original)
        assertEquals(0, fixture.writes)
    }

    @Test fun `legacy solid emitters are accepted but ordinary blocks are not`() {
        assertTrue(LightBlock.supports(data(Material.REDSTONE_BLOCK)))
        assertTrue(LightBlock.supports(data(Material.BEACON)))
        assertTrue(LightBlock.supports(data(Material.GLOWSTONE)))
        assertTrue(LightBlock.supports(data(Material.SHROOMLIGHT)))
        assertFalse(LightBlock.supports(data(Material.STONE)))
        assertFalse(LightBlock.supports(data(Material.TORCH)))
    }
}
