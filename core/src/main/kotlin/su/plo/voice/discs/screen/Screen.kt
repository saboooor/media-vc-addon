package su.plo.voice.discs.screen

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import java.util.UUID

data class Screen(
    val name: String,
    val worldName: String,
    val facingName: String,
    val columns: Int,
    val rows: Int,
    val frameUuids: List<List<String>>,
    val mapIds: List<List<Int>>,
    var jukeboxWorld: String? = null,
    var jukeboxX: Int? = null,
    var jukeboxY: Int? = null,
    var jukeboxZ: Int? = null,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val centerZ: Double = 0.0,
) {
    val facing: BlockFace
        get() = try {
            BlockFace.valueOf(facingName)
        } catch (_: Exception) {
            BlockFace.NORTH
        }

    val totalWidthPixels: Int
        get() = columns * 128

    val totalHeightPixels: Int
        get() = rows * 128

    val totalFrames: Int
        get() = columns * rows

    fun isLinkedTo(block: Block): Boolean {
        return jukeboxWorld == block.world.name &&
                jukeboxX == block.x &&
                jukeboxY == block.y &&
                jukeboxZ == block.z
    }

    fun hasLinkedJukebox(): Boolean {
        return jukeboxWorld != null && jukeboxX != null && jukeboxY != null && jukeboxZ != null
    }

    fun linkJukebox(block: Block) {
        jukeboxWorld = block.world.name
        jukeboxX = block.x
        jukeboxY = block.y
        jukeboxZ = block.z
    }

    fun unlinkJukebox() {
        jukeboxWorld = null
        jukeboxX = null
        jukeboxY = null
        jukeboxZ = null
    }

    fun getCenterLocation(world: World? = null): Location? {
        val targetWorld = world ?: Bukkit.getWorld(worldName) ?: return null
        return Location(targetWorld, centerX, centerY, centerZ)
    }

    fun containsFrameUuid(uuid: UUID): Boolean {
        val str = uuid.toString()
        for (row in frameUuids) {
            if (row.contains(str)) return true
        }
        return false
    }
}

