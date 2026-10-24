package ca.saboor.discs.command.subcommand

import kotlin.math.floor

/** Block centers determine the three-dimensional search radius, including hidden floors. */
internal class NearbyJukeboxQuery(val x: Double, val y: Double, val z: Double, val radius: Int) {
    init { require(radius in 1..256) }
    val minChunkX = floor((x - radius) / 16).toInt()
    val maxChunkX = floor((x + radius) / 16).toInt()
    val minChunkZ = floor((z - radius) / 16).toInt()
    val maxChunkZ = floor((z + radius) / 16).toInt()
    fun distanceSquared(blockX: Int, blockY: Int, blockZ: Int): Double {
        val dx = blockX + 0.5 - x
        val dy = blockY + 0.5 - y
        val dz = blockZ + 0.5 - z
        return dx * dx + dy * dy + dz * dz
    }
    fun contains(blockX: Int, blockY: Int, blockZ: Int) = distanceSquared(blockX, blockY, blockZ) <= radius.toDouble() * radius
}
