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

    val colorCycle = listOf(
        "RED",
        "ORANGE",
        "YELLOW",
        "LIME",
        "CYAN",
        "LIGHT_BLUE",
        "BLUE",
        "PURPLE",
        "MAGENTA",
        "PINK",
    )

    private val dyePrefixes = setOf(
        "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE",
        "YELLOW", "LIME", "PINK", "GRAY",
        "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE",
        "BROWN", "GREEN", "RED", "BLACK",
    )

    // Ordered longest-suffix-first so _GLAZED_TERRACOTTA matches before _TERRACOTTA,
    // _CONCRETE_POWDER before _CONCRETE, _STAINED_GLASS_PANE before _STAINED_GLASS, etc.
    private val dyedSuffixes = listOf(
        "GLAZED_TERRACOTTA",
        "TERRACOTTA",
        "CONCRETE_POWDER",
        "CONCRETE",
        "STAINED_GLASS_PANE",
        "STAINED_GLASS",
        "WOOL_STAIRS",
        "WOOL_SLAB",
        "WOOL",
        "CARPET",
        "WALL_BANNER",
        "BANNER",
        "CANDLE",
    )

    val woolColors = colorCycle.map { Material.valueOf("${it}_WOOL") }

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
        "Concrete / Powder",
        "Stained Glass / Panes",
        "Terracotta / Glazed",
        "Carpets",
        "Banners",
        "Candles",
        "Redstone Block",
        "Magma Block",
        "Crying Obsidian",
        "Respawn Anchor",
        "End Portal Frame",
    )

    internal fun dyedFamily(material: Material): String? {
        val name = material.name
        val suffix = dyedSuffixes.firstOrNull { name.endsWith("_$it") } ?: return null
        val prefix = name.removeSuffix("_$suffix")
        return if (prefix in dyePrefixes) suffix else null
    }

    fun isWool(material: Material): Boolean = material.name.endsWith("_WOOL")
    fun isDyedColorBlock(material: Material): Boolean = dyedFamily(material) != null
    fun woolForStep(step: Int): Material = woolColors[step.mod(woolColors.size)]
    fun dyedMaterialForStep(family: String, step: Int): Material? =
        Material.getMaterial("${colorCycle[step.mod(colorCycle.size)]}_$family")
    fun dyedOffMaterial(family: String): Material? = Material.getMaterial("BLACK_$family")

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
        if (isDyedColorBlock(data.material) || data.material.name == "CANDLE") return true
        if (!data.material.isSolid) return false
        if (data.material == Material.REDSTONE_BLOCK || data.material == Material.BEACON) return true
        val probe = data.clone()
        if (probe is Lightable) probe.isLit = true
        return if (emission != null) (emission.invoke(probe) as Int) > 0 else
            data.material.name in setOf("REDSTONE_LAMP", "GLOWSTONE", "SHROOMLIGHT", "SEA_LANTERN", "JACK_O_LANTERN", "MAGMA_BLOCK", "BEACON", "END_PORTAL_FRAME", "CRYING_OBSIDIAN", "RESPAWN_ANCHOR")
    }

    fun original(saved: String?): BlockData? = saved?.let { runCatching { Bukkit.createBlockData(it) }.getOrNull() }
    fun matches(block: Block, original: BlockData): Boolean {
        val family = dyedFamily(original.material)
        return when {
            family != null -> dyedFamily(block.type) == family || block.type == Material.BLACK_CONCRETE
            else -> block.type == original.material || (original !is Lightable && block.type == Material.BLACK_CONCRETE)
        }
    }

    private fun resolveVariant(
        original: BlockData,
        targetMat: Material,
        colorResolver: (Material) -> BlockData?
    ): BlockData {
        if (targetMat == original.material) return original
        val raw = runCatching { original.asString }.getOrNull()
        val props = raw?.substringAfter('[', "")
        if (!props.isNullOrEmpty()) {
            val withProps = runCatching { Bukkit.createBlockData("minecraft:${targetMat.name.lowercase()}[$props") }.getOrNull()
            if (withProps != null) return withProps
        }
        return colorResolver(targetMat) ?: original
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
        val family = dyedFamily(original.material)
        if (family != null) {
            val targetMat = (if (!on) dyedOffMaterial(family)
            else if (colorStep >= 0) dyedMaterialForStep(family, colorStep)
            else original.material) ?: original.material
            val target = resolveVariant(original, targetMat, colorResolver).let { resolved ->
                if (resolved is Lightable) resolved.clone().also { (it as Lightable).isLit = on } else resolved
            }
            if (block.blockData != target) {
                if (block.type == original.material && (family == "BANNER" || family == "WALL_BANNER")) {
                    val state = runCatching { block.state }.getOrNull()
                    if (state is TileState) tileStates.putIfAbsent(block, state)
                }
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
        if (original is Lightable && dyedFamily(original.material) == null) update(block, original, false)
        else if (block.blockData != original) {
            block.setBlockData(original.clone(), original.material == Material.REDSTONE_BLOCK)
            tileStates.remove(block)?.update(true, false)
        }
    }
}
