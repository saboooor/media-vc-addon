package ca.saboor.discs.visualizer

import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Lightable

/** Owns only the original light material and its temporary black-concrete off state. */
object LightBlock {
    private val tileStates = ConcurrentHashMap<Block, BlockState>()
    private val emission = runCatching { BlockData::class.java.getMethod("getLightEmission") }.getOrNull()

    fun supports(data: BlockData): Boolean {
        if (!data.material.isSolid) return false
        if (data.material == Material.REDSTONE_BLOCK || data.material == Material.BEACON) return true
        val probe = data.clone()
        if (probe is Lightable) probe.isLit = true
        return if (emission != null) (emission.invoke(probe) as Int) > 0 else
            data.material.name in setOf("REDSTONE_LAMP", "GLOWSTONE", "SHROOMLIGHT", "SEA_LANTERN", "JACK_O_LANTERN", "MAGMA_BLOCK", "BEACON", "END_PORTAL_FRAME", "CRYING_OBSIDIAN", "RESPAWN_ANCHOR")
    }

    fun original(saved: String?): BlockData? = saved?.let { runCatching { Bukkit.createBlockData(it) }.getOrNull() }
    fun matches(block: Block, original: BlockData): Boolean = block.type == original.material ||
        (original !is Lightable && block.type == Material.BLACK_CONCRETE)

    fun update(block: Block, original: BlockData, on: Boolean) {
        if (!matches(block, original)) { tileStates.remove(block); return }
        // Beacon beams are handled by BeaconShutter; never replace a beacon during playback.
        if (original.material == Material.BEACON) return
        if (original is Lightable) {
            val data = block.blockData as? Lightable ?: return
            val lit = on || block.isBlockPowered || block.isBlockIndirectlyPowered
            if (data.isLit != lit) block.setBlockData(data.apply { isLit = lit }, false)
        } else {
            val target = if (on) original else Bukkit.createBlockData(Material.BLACK_CONCRETE)
            if (block.blockData != target) {
                if (!on && block.type == original.material) {
                    val state = block.state
                    if (state is TileState) tileStates.putIfAbsent(block, state)
                }
                block.setBlockData(target, original.material == Material.REDSTONE_BLOCK)
                if (on) tileStates.remove(block)?.update(true, false)
            }
        }
    }

    fun restore(block: Block, original: BlockData) {
        if (!matches(block, original)) { tileStates.remove(block); return }
        if (original is Lightable) update(block, original, false)
        else if (block.blockData != original) {
            block.setBlockData(original.clone(), original.material == Material.REDSTONE_BLOCK)
            tileStates.remove(block)?.update(true, false)
        }
    }
}
