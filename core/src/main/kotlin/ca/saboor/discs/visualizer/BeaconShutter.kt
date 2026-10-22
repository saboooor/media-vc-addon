package ca.saboor.discs.visualizer

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/** Only an originally empty space above a beacon may be used as a shutter. */
object BeaconShutter {
    fun space(beacon: Block): Block? = if (beacon.y + 1 < beacon.world.maxHeight)
        beacon.getRelative(0, 1, 0) else null

    fun capture(beacon: Block): BlockData? = space(beacon)?.blockData?.takeIf { it.material.isAir }

    fun update(beacon: Block, empty: BlockData, closed: BlockData, on: Boolean) {
        val space = space(beacon) ?: return
        if (!empty.material.isAir || space.type != empty.material && space.type != closed.material) return
        val target = if (on) empty else closed
        if (space.blockData != target) space.setBlockData(target.clone(), false)
    }
}
