package ca.saboor.discs.screen

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID
import org.bukkit.block.BlockFace
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenManagerTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `screen fps persists and follows server maximum for old saves`() {
        val screen = Screen("tv", "world", "NORTH", 1, 1, emptyList(), emptyList())
        val oldSave = gson.toJson(screen)
        val restored = gson.fromJson(oldSave, Screen::class.java)
        assertNull(restored.videoFps)
        assertEquals(30, restored.effectiveVideoFps(30))
        restored.videoFps = 12
        val saved = gson.fromJson(gson.toJson(restored), Screen::class.java)
        assertEquals(12, saved.videoFps)
        assertEquals(12, saved.effectiveVideoFps(30))
        assertEquals(10, saved.effectiveVideoFps(10))
        saved.videoFps = -5
        assertEquals(1, saved.effectiveVideoFps(30))
        saved.videoFps = null
        assertEquals(15, saved.effectiveVideoFps(15))
    }

    @Test
    fun `screen dithering persists and follows server default for old saves`() {
        val screen = Screen("tv", "world", "NORTH", 1, 1, emptyList(), emptyList())
        val oldSave = gson.toJson(screen)
        val restored = gson.fromJson(oldSave, Screen::class.java)
        assertNull(restored.dithering)
        assertTrue(restored.effectiveDithering(true))
        assertFalse(restored.effectiveDithering(false))
        restored.dithering = false
        val saved = gson.fromJson(gson.toJson(restored), Screen::class.java)
        assertEquals(false, saved.dithering)
        assertFalse(saved.effectiveDithering(true))
        saved.dithering = true
        assertTrue(saved.effectiveDithering(false))
        assertEquals(DitherMode.IGN, saved.effectiveDitherMode("ign"))
        assertEquals(DitherMode.BAYER, saved.effectiveDitherMode("bayer"))
        saved.ditherMode = "bayer"
        saved.distanceAdaptiveDithering = false
        val savedWithMode = gson.fromJson(gson.toJson(saved), Screen::class.java)
        assertEquals(DitherMode.BAYER, savedWithMode.effectiveDitherMode("ign"))
        assertFalse(savedWithMode.effectiveDistanceAdaptiveDithering(true))
    }

    @Test
    fun `screen distance accounts for multi-frame span`() {
        val screen = Screen(
            name = "cinema",
            worldName = "world",
            facingName = BlockFace.SOUTH.name,
            columns = 5,
            rows = 3,
            frameUuids = emptyList(),
            mapIds = emptyList(),
            centerX = 10.5,
            centerY = 65.5,
            centerZ = 20.5
        )
        // Standing 4 blocks in front of the left edge (x = 8.5 is within [8.5, 12.5])
        assertEquals(4.0, screen.distanceTo(8.5, 65.5, 24.5), 1e-6)
        // Moving 3 blocks past the right edge (x = 15.5 is 3 blocks past 12.5) and 4 blocks in front
        assertEquals(5.0, screen.distanceTo(15.5, 65.5, 24.5), 1e-6)
    }

    @Test
    fun `screen serializes and deserializes correctly with json`() {
        val screen = Screen(
            name = "cinema",
            worldName = "world",
            facingName = BlockFace.SOUTH.name,
            columns = 3,
            rows = 2,
            frameUuids = listOf(
                listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
            ),
            mapIds = listOf(
                listOf(101, 102, 103),
                listOf(104, 105, 106)
            ),
            jukeboxWorld = "world",
            jukeboxX = 100,
            jukeboxY = 64,
            jukeboxZ = 200,
            centerX = 101.5,
            centerY = 64.5,
            centerZ = 200.5
        )

        val map = mapOf("cinema" to screen)
        val json = gson.toJson(map)
        assertNotNull(json)

        val type = object : TypeToken<Map<String, Screen>>() {}.type
        val loaded: Map<String, Screen> = gson.fromJson(json, type)

        val retrieved = loaded["cinema"]
        assertNotNull(retrieved)
        assertEquals("cinema", retrieved!!.name)
        assertEquals("world", retrieved.worldName)
        assertEquals(BlockFace.SOUTH, retrieved.facing)
        assertEquals(3, retrieved.columns)
        assertEquals(2, retrieved.rows)
        assertEquals(384, retrieved.totalWidthPixels)
        assertEquals(256, retrieved.totalHeightPixels)
        assertEquals(101, retrieved.mapIds[0][0])
        assertEquals(106, retrieved.mapIds[1][2])
        assertTrue(retrieved.hasLinkedJukebox())
        assertEquals(100, retrieved.jukeboxX)
    }

    @Test
    fun `screen linking and unlinking modifies state correctly`() {
        val screen = Screen(
            name = "tv",
            worldName = "world",
            facingName = BlockFace.NORTH.name,
            columns = 2,
            rows = 2,
            frameUuids = emptyList(),
            mapIds = emptyList()
        )

        assertFalse(screen.hasLinkedJukebox())

        screen.jukeboxWorld = "world"
        screen.jukeboxX = 50
        screen.jukeboxY = 70
        screen.jukeboxZ = -30

        assertTrue(screen.hasLinkedJukebox())

        screen.unlinkJukebox()
        assertFalse(screen.hasLinkedJukebox())
        assertNull(screen.jukeboxWorld)
        assertNull(screen.jukeboxX)
    }
}

