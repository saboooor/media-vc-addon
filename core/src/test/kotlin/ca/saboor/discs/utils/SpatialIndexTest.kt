package ca.saboor.discs.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialIndexTest {
    @Test fun `moving worlds cells and disconnecting leave no old audience entries`() {
        val index = SpatialIndex<String, String>()
        index.put("player", "world", -32.1, 64.0, 31.9, "old")
        assertEquals(listOf("old"), index.nearby("world", -32.0, 64.0, 32.0, 1.0))
        index.put("player", "nether", 100.0, 50.0, 100.0, "new")
        assertTrue(index.nearby("world", -32.0, 64.0, 32.0, 2.0).isEmpty())
        assertEquals(listOf("new"), index.nearby("nether", 100.0, 50.0, 100.0, 0.0))
        index.remove("player")
        assertTrue(index.nearby("nether", 100.0, 50.0, 100.0, 64.0).isEmpty())
    }

    @Test fun `indexed audience matches a full three dimensional scan at cell boundaries`() {
        val index = SpatialIndex<Int, Int>()
        val random = kotlin.random.Random(123)
        val points = List(1000) { Triple(random.nextDouble(-256.0, 256.0), random.nextDouble(-50.0, 150.0), random.nextDouble(-256.0, 256.0)) }
        points.forEachIndexed { i, (x, y, z) -> index.put(i, if (i % 2 == 0) "world" else "other", x, y, z, i) }
        for (range in listOf(0.0, 1.0, 32.0, 64.0, 128.0)) for (x in listOf(-32.0, -0.01, 0.0, 31.99)) {
            val expected = points.indices.filter { i ->
                val p = points[i]
                i % 2 == 0 && (p.first - x) * (p.first - x) + (p.second - 50) * (p.second - 50) + p.third * p.third <= range * range
            }.toSet()
            assertEquals(expected, index.nearby("world", x, 50.0, 0.0, range).toSet())
        }
        index.clear()
        assertTrue(index.nearby("world", 0.0, 0.0, 0.0, 256.0).isEmpty())
    }
}
