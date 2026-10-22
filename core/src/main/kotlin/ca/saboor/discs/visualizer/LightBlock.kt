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

    val woolColors = listOf(
        Material.RED_WOOL,
        Material.ORANGE_WOOL,
        Material.YELLOW_WOOL,
        Material.LIME_WOOL,
        Material.CYAN_WOOL,
        Material.LIGHT_BLUE_WOOL,
        Material.BLUE_WOOL,
        Material.PURPLE_WOOL,
        Material.MAGENTA_WOOL,
        Material.PINK_WOOL,
    )

    val supportedBlockNames = listOf(
        "Redstone Lamp",
        "Copper Bulb",
        "Sea Lantern",
        "Glowstone",
        "Shroomlight",
        "Froglight",
        "Jack o'Lantern",
        "Beacon (color-changing beam)",
        "Wool (color-changing)",
        "Redstone Block",
        "Magma Block",
        "Crying Obsidian",
        "Respawn Anchor",
        "End Portal Frame",
    )

    fun isWool(material: Material): Boolean = material.name.endsWith("_WOOL")
    fun woolForStep(step: Int): Material = woolColors[step.mod(woolColors.size)]

    fun bandColorOffset(band: LightBand): Int = when (band) {
        LightBand.DEEP_BASS -> 0
        LightBand.BASS -> 1
        LightBand.LOW_MIDS -> 2
        LightBand.MID -> 3
        LightBand.VOCALS -> 4
        LightBand.HIGHS -> 6
        LightBand.SPARKLE -> 8
        LightBand.FULL -> 0
    }

    fun supports(data: BlockData): Boolean {
        if (!data.material.isSolid) return false
        if (data.material == Material.REDSTONE_BLOCK || data.material == Material.BEACON || isWool(data.material)) return true
        val probe = data.clone()
        if (probe is Lightable) probe.isLit = true
        return if (emission != null) (emission.invoke(probe) as Int) > 0 else
            data.material.name in setOf("REDSTONE_LAMP", "GLOWSTONE", "SHROOMLIGHT", "SEA_LANTERN", "JACK_O_LANTERN", "MAGMA_BLOCK", "BEACON", "END_PORTAL_FRAME", "CRYING_OBSIDIAN", "RESPAWN_ANCHOR")
    }

    fun original(saved: String?): BlockData? = saved?.let { runCatching { Bukkit.createBlockData(it) }.getOrNull() }
    fun matches(block: Block, original: BlockData): Boolean = when {
        isWool(original.material) -> isWool(block.type) || block.type == Material.BLACK_CONCRETE
        else -> block.type == original.material || (original !is Lightable && block.type == Material.BLACK_CONCRETE)
    }

    fun update(
        block: Block,
        original: BlockData,
        on: Boolean,
        colorStep: Int = -1,
        colorResolver: (Material) -> BlockData? = { runCatching { Bukkit.createBlockData(it) }.getOrNull() }
    ) {
        if (!matches(block, original)) { tileStates.remove(block); return }
        // Beacon beams are handled by BeaconShutter; never replace a beacon during playback.
        if (original.material == Material.BEACON) return
        if (isWool(original.material)) {
            val targetMat = if (!on) Material.BLACK_WOOL else if (colorStep >= 0) woolForStep(colorStep) else original.material
            val target = if (targetMat == original.material) original else (colorResolver(targetMat) ?: original)
            if (block.blockData != target) {
                block.setBlockData(target, false)
            }
            return
        }
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
