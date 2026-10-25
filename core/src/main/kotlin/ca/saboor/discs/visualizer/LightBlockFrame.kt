package ca.saboor.discs.visualizer

import ca.saboor.discs.event.JukeboxBassLamps.Position

/** A complete frame is published only after all region writes have finished. */
internal class LightBlockFrame(blocks: Map<Position, String>) {
    data class Section(val world: String, val x: Int, val y: Int, val z: Int)
    val blocks = blocks.toMap()
    val sections = this.blocks.entries.groupBy { (pos, _) ->
        Section(pos.world, pos.x shr 4, pos.y shr 4, pos.z shr 4)
    }
}
