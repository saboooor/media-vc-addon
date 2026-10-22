package ca.saboor.discs.screen

import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

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
    var subtitlesEnabled: Boolean = true,
    var subtitleLanguage: String? = null,
    var subtitleTrackIndex: Int? = null,
    var subtitleMediaId: String? = null,
    @Volatile var videoFps: Int? = null,
) {
    fun effectiveVideoFps(serverMax: Int): Int {
        val maximum = serverMax.coerceIn(5, 30)
        return (videoFps ?: maximum).coerceIn(1, maximum)
    }

    @Transient
    private var cachedFlatMapIds: List<Int>? = null

    val flatMapIds: List<Int>
        get() = cachedFlatMapIds ?: mapIds.flatten().also { cachedFlatMapIds = it }

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

