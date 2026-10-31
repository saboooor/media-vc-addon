package ca.saboor.discs.utils

import kotlin.math.floor

/** Small immutable position entries, indexed by world and horizontal cell. */
internal class SpatialIndex<K, V>(private val cellSize: Int = 32) {
    private data class Cell(val world: String, val x: Int, val z: Int)
    private data class Entry<V>(val cell: Cell, val x: Double, val y: Double, val z: Double, val value: V)
    private val entries = HashMap<K, Entry<V>>()
    private val cells = HashMap<Cell, MutableMap<K, Entry<V>>>()
    private fun cell(value: Double) = floor(value / cellSize).toInt()

    @Synchronized fun put(key: K, world: String, x: Double, y: Double, z: Double, value: V) {
        remove(key)
        val entry = Entry(Cell(world, cell(x), cell(z)), x, y, z, value)
        entries[key] = entry
        cells.getOrPut(entry.cell) { HashMap() }[key] = entry
    }
    @Synchronized fun remove(key: K) {
        val old = entries.remove(key) ?: return
        cells[old.cell]?.let { bucket -> if (bucket.apply { remove(key) }.isEmpty()) cells.remove(old.cell) }
    }
    @Synchronized fun nearby(world: String, x: Double, y: Double, z: Double, range: Double): List<V> {
        val result = ArrayList<V>()
        for (cx in cell(x - range)..cell(x + range)) for (cz in cell(z - range)..cell(z + range)) {
            for (entry in cells[Cell(world, cx, cz)]?.values.orEmpty()) {
                val dx = entry.x - x; val dy = entry.y - y; val dz = entry.z - z
                if (dx * dx + dy * dy + dz * dz <= range * range) result.add(entry.value)
            }
        }
        return result
    }
    @Synchronized fun clear() { entries.clear(); cells.clear() }
}
