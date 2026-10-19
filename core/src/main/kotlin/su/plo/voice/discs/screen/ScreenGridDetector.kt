package su.plo.voice.discs.screen

import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import java.util.UUID

object ScreenGridDetector {

    data class DetectionResult(
        val columns: Int,
        val rows: Int,
        val facing: BlockFace,
        val orderedFrames: List<List<ItemFrame>>,
        val centerX: Double,
        val centerY: Double,
        val centerZ: Double,
    )

    class DetectionException(message: String) : Exception(message)

    fun detectGrid(corner1: ItemFrame, corner2: ItemFrame): DetectionResult {
        if (corner1.world != corner2.world) {
            throw DetectionException("Both corner item frames must be in the same world.")
        }
        val facing = corner1.facing
        if (facing != corner2.facing) {
            throw DetectionException("Both corner item frames must face the same direction (found $facing and ${corner2.facing}).")
        }

        val b1x = corner1.location.blockX
        val b1y = corner1.location.blockY
        val b1z = corner1.location.blockZ

        val b2x = corner2.location.blockX
        val b2y = corner2.location.blockY
        val b2z = corner2.location.blockZ

        val minX = minOf(b1x, b2x)
        val maxX = maxOf(b1x, b2x)
        val minY = minOf(b1y, b2y)
        val maxY = maxOf(b1y, b2y)
        val minZ = minOf(b1z, b2z)
        val maxZ = maxOf(b1z, b2z)

        when (facing) {
            BlockFace.NORTH, BlockFace.SOUTH -> {
                if (kotlin.math.abs(b1z - b2z) > 1) {
                    throw DetectionException("Wall item frames facing $facing must be on the same wall plane (Z mismatch).")
                }
            }
            BlockFace.EAST, BlockFace.WEST -> {
                if (kotlin.math.abs(b1x - b2x) > 1) {
                    throw DetectionException("Wall item frames facing $facing must be on the same wall plane (X mismatch).")
                }
            }
            BlockFace.UP, BlockFace.DOWN -> {
                if (kotlin.math.abs(b1y - b2y) > 1) {
                    throw DetectionException("Horizontal item frames facing $facing must be on the same level (Y mismatch).")
                }
            }
            else -> throw DetectionException("Unsupported facing direction: $facing")
        }

        val columns: Int
        val rows: Int

        when (facing) {
            BlockFace.NORTH, BlockFace.SOUTH -> {
                columns = maxX - minX + 1
                rows = maxY - minY + 1
            }
            BlockFace.EAST, BlockFace.WEST -> {
                columns = maxZ - minZ + 1
                rows = maxY - minY + 1
            }
            BlockFace.UP, BlockFace.DOWN -> {
                columns = maxX - minX + 1
                rows = maxZ - minZ + 1
            }
            else -> throw DetectionException("Unsupported facing: $facing")
        }

        if (columns <= 0 || rows <= 0) {
            throw DetectionException("Invalid screen dimensions: ${columns}x${rows}")
        }
        if (columns > 32 || rows > 32) {
            throw DetectionException("Screen dimensions exceed maximum supported size of 32x32 frames (got ${columns}x${rows}).")
        }

        val centerLocation = Location(
            corner1.world,
            (minX + maxX) / 2.0 + 0.5,
            (minY + maxY) / 2.0 + 0.5,
            (minZ + maxZ) / 2.0 + 0.5
        )

        val radiusX = (maxX - minX + 2).toDouble()
        val radiusY = (maxY - minY + 2).toDouble()
        val radiusZ = (maxZ - minZ + 2).toDouble()

        val candidateFrames = corner1.world.getNearbyEntities(centerLocation, radiusX, radiusY, radiusZ)
            .filterIsInstance<ItemFrame>()
            .filter { it.facing == facing }

        val grid = ArrayList<List<ItemFrame>>(rows)

        for (r in 0 until rows) {
            val rowList = ArrayList<ItemFrame>(columns)
            for (c in 0 until columns) {
                val targetBlockX: Int
                val targetBlockY: Int
                val targetBlockZ: Int

                when (facing) {
                    BlockFace.SOUTH -> {
                        targetBlockX = minX + c
                        targetBlockY = maxY - r
                        targetBlockZ = b1z
                    }
                    BlockFace.NORTH -> {
                        targetBlockX = maxX - c
                        targetBlockY = maxY - r
                        targetBlockZ = b1z
                    }
                    BlockFace.EAST -> {
                        targetBlockX = b1x
                        targetBlockY = maxY - r
                        targetBlockZ = maxZ - c
                    }
                    BlockFace.WEST -> {
                        targetBlockX = b1x
                        targetBlockY = maxY - r
                        targetBlockZ = minZ + c
                    }
                    BlockFace.UP, BlockFace.DOWN -> {
                        targetBlockX = minX + c
                        targetBlockY = b1y
                        targetBlockZ = minZ + r
                    }
                    else -> throw DetectionException("Unsupported facing: $facing")
                }

                val frame = candidateFrames.firstOrNull {
                    it.location.blockX == targetBlockX && it.location.blockY == targetBlockY && it.location.blockZ == targetBlockZ
                } ?: candidateFrames.minByOrNull {
                    val loc = it.location
                    val dx = loc.x - (targetBlockX + 0.5)
                    val dy = loc.y - (targetBlockY + 0.5)
                    val dz = loc.z - (targetBlockZ + 0.5)
                    dx * dx + dy * dy + dz * dz
                }?.takeIf {
                    val loc = it.location
                    val dx = loc.x - (targetBlockX + 0.5)
                    val dy = loc.y - (targetBlockY + 0.5)
                    val dz = loc.z - (targetBlockZ + 0.5)
                    (dx * dx + dy * dy + dz * dz) < 0.75
                }

                if (frame == null) {
                    throw DetectionException("Missing item frame at column ${c + 1}, row ${r + 1} (expected around block $targetBlockX, $targetBlockY, $targetBlockZ).")
                }
                rowList.add(frame)
            }
            grid.add(rowList)
        }

        return DetectionResult(
            columns = columns,
            rows = rows,
            facing = facing,
            orderedFrames = grid,
            centerX = centerLocation.x,
            centerY = centerLocation.y,
            centerZ = centerLocation.z
        )
    }
}
