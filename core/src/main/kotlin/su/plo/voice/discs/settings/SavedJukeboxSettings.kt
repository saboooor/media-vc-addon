package su.plo.voice.discs.settings

import com.google.gson.Gson
import su.plo.voice.discs.event.HologramOffset
import su.plo.voice.discs.event.JukeboxBassLamps
import su.plo.voice.discs.visualizer.VisualizerInstance

/** Item/block payload; playback jobs and queued disc contents are deliberately excluded. */
data class SavedJukeboxSettings(
    val version: Int = 1,
    val volume: Int = 100,
    val loop: Boolean = false,
    val shuffle: Boolean = false,
    val stereo: Int = 0,
    val crossfade: Int = 3,
    val autoMix: Boolean = false,
    val lastCrossfade: Int = 3,
    val hologram: HologramOffset = HologramOffset(),
    val visualizers: List<VisualizerInstance> = emptyList(),
    val lamps: List<JukeboxBassLamps.Position> = emptyList(),
    val lampBands: List<su.plo.voice.discs.visualizer.LightBand> = emptyList(),
    val screenName: String? = null,
    val leftNoteBlock: JukeboxBassLamps.Position? = null,
    val rightNoteBlock: JukeboxBassLamps.Position? = null,
    val noteBlock: JukeboxBassLamps.Position? = null,
    val filterConfig: su.plo.voice.discs.audio.AudioFilterConfig = su.plo.voice.discs.audio.AudioFilterConfig(),
) {
    fun validate(): SavedJukeboxSettings {
        require(stereo in 0..3)
        require(version == 1 && volume in 0..100 && crossfade in 0..30 && lastCrossfade in 1..30)
        require(filterConfig.speed in 0.25..4.0)
        require(hologram.x.isFinite() && hologram.y.isFinite() && hologram.z.isFinite())
        require(visualizers.size <= 8 && visualizers.map { it.id }.distinct().size == visualizers.size)
        for (instance in visualizers) {
            require(instance.id in 1..1_000_000)
            requireNotNull(instance.kind); requireNotNull(instance.facing)
            val layout = instance.layout
            require(listOf(layout.x, layout.y, layout.z).all { it.isFinite() && it in -16f..16f })
            require(layout.width in 0.25f..4f && layout.height in 0.25f..4f)
        }
        require(lamps.size <= 1024)
        require(lampBands.isEmpty() || lampBands.size == lamps.size)
        lampBands.forEach { requireNotNull(it) }
        for (lamp in lamps + listOfNotNull(noteBlock, leftNoteBlock, rightNoteBlock)) {
            java.util.UUID.fromString(lamp.world)
            require(lamp.x in -30_000_000..30_000_000 && lamp.z in -30_000_000..30_000_000 && lamp.y in -4096..4096)
        }
        require(screenName == null || screenName.length <= 128)
        return this
    }
    fun encode(): String = Gson().toJson(validate())
    companion object {
        fun decode(json: String): SavedJukeboxSettings? = runCatching {
            require(json.length <= 262144)
            Gson().fromJson(json, SavedJukeboxSettings::class.java).validate()
        }.getOrNull()
    }
}


