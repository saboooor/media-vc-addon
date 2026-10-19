package su.plo.voice.discs

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import java.util.concurrent.CopyOnWriteArrayList

/** One logical playback, shared by the PV and SVC decoders. */
class PlaybackControls(initialVolume: Int = 100, initialGain: Double = 1.0) {
    @Volatile var sourceFormatDescription: String? = null
    @Volatile var sourceAvailableFormats: String? = null
    @Volatile private var gain = initialGain.coerceIn(0.0, 1.0)
    @Volatile var transitionPartner: PlaybackControls? = null
    var onPauseChanged: ((Boolean) -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    private fun effectiveVolume() = (volume * gain).toInt().coerceIn(0, 100)
    @Synchronized fun setGain(value: Double) {
        gain = value.coerceIn(0.0, 1.0)
        players.forEach { it.volume = effectiveVolume() }
    }
    @Synchronized private fun applyPaused(value: Boolean) {
        paused = value
        players.forEach { it.isPaused = paused }
        onPauseChanged?.invoke(paused)
    }
    @Synchronized private fun applyVolume(value: Int) {
        volume = value.coerceIn(0, 100)
        players.forEach { it.volume = effectiveVolume() }
    }

    private val players = CopyOnWriteArrayList<AudioPlayer>()
    @Volatile var title: String = "Loading..."
    @Volatile var paused: Boolean = false
        private set
    @Volatile var volume: Int = initialVolume.coerceIn(0, 100)
        private set
    val position: Long get() = players.firstOrNull()?.playingTrack?.position ?: 0
    val duration: Long get() = players.firstOrNull()?.playingTrack?.duration ?: 0
    val isStream: Boolean get() = players.firstOrNull()?.playingTrack?.info?.isStream == true
    val seekable: Boolean get() = players.firstOrNull()?.playingTrack?.isSeekable == true

    @Synchronized fun attach(player: AudioPlayer) {
        players.firstOrNull()?.playingTrack?.let { current ->
            player.playingTrack?.takeIf { it.isSeekable }?.position = current.position
        }
        player.isPaused = paused
        player.volume = effectiveVolume()
        players.add(player)
    }

    fun detach(player: AudioPlayer) { players.remove(player) }

    fun togglePause() {
        applyPaused(!paused)
        transitionPartner?.applyPaused(paused)
    }

    fun changeVolume(delta: Int) {
        applyVolume(volume + delta)
        transitionPartner?.applyVolume(volume)
    }

    @Synchronized fun seekTo(positionMs: Long) {
        if (!seekable) return
        val target = positionMs.coerceIn(0, (duration - 1).coerceAtLeast(0))
        players.forEach { it.playingTrack?.takeIf { track -> track.isSeekable }?.position = target }
        onSeek?.invoke(target)
    }
}
