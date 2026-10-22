package ca.saboor.discs.visualizer

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/** Only an originally empty space above a beacon may be used as a shutter. */
object BeaconShutter {
    val glassColors = listOf(
        Material.RED_STAINED_GLASS,
        Material.ORANGE_STAINED_GLASS,
        Material.YELLOW_STAINED_GLASS,
        Material.LIME_STAINED_GLASS,
        Material.CYAN_STAINED_GLASS,
        Material.LIGHT_BLUE_STAINED_GLASS,
        Material.BLUE_STAINED_GLASS,
        Material.PURPLE_STAINED_GLASS,
        Material.MAGENTA_STAINED_GLASS,
        Material.PINK_STAINED_GLASS,
    )

    fun isShutterGlass(material: Material): Boolean = material in glassColors
    fun glassForStep(step: Int): Material = glassColors[step.mod(glassColors.size)]

    fun space(beacon: Block): Block? = if (beacon.y + 1 < beacon.world.maxHeight)
        beacon.getRelative(0, 1, 0) else null

    fun capture(beacon: Block): BlockData? = space(beacon)?.blockData?.takeIf { it.material.isAir }

    fun update(beacon: Block, empty: BlockData, closed: BlockData, on: Boolean, litData: BlockData = empty) {
        val space = space(beacon) ?: return
        if (!empty.material.isAir || (space.type != empty.material && space.type != closed.material && !isShutterGlass(space.type))) return
        val target = if (on) litData else closed
        if (space.blockData != target) space.setBlockData(target.clone(), false)
    }
}
