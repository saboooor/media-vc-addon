package su.plo.voice.discs

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import java.util.concurrent.CopyOnWriteArrayList
import su.plo.voice.discs.visualizer.SpectrumAnalyzer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory

/** One logical playback, shared by the PV and SVC decoders. */
class PlaybackControls(initialVolume: Int = 100, initialGain: Double = 1.0) {
    @Volatile var failure: String? = null
        private set
    @Synchronized fun fail(message: String) {
        if (failure == null) failure = message
    }
    val spectrum = SpectrumAnalyzer()
    @Volatile private var visualizing = false
    private val analysisConsumers = mutableSetOf<String>()
    @Synchronized fun setVisualizing(enabled: Boolean, consumer: String = "visualizer") {
        if (enabled) analysisConsumers.add(consumer) else analysisConsumers.remove(consumer)
        val active = analysisConsumers.isNotEmpty()
        if (visualizing == active) return
        visualizing = active
        spectrum.reset()
        players.firstOrNull()?.setFilterFactory(if (active) spectrumFilter() else null)
    }
    private fun spectrumFilter() = PcmFilterFactory { _, format, output ->
        listOf(object : FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                if (visualizing) spectrum.accept(input, offset, length, format.sampleRate)
                output.process(input, offset, length)
            }
            override fun seekPerformed(requested: Long, provided: Long) { spectrum.reset() }
            override fun flush() {}
            override fun close() {}
        })
    }
    @Volatile var sourceFormatDescription: String? = null
    @Volatile var sourceAvailableFormats: String? = null
    @Volatile private var gain = initialGain.coerceIn(0.0, 1.0)
    @Volatile var transitionPartner: PlaybackControls? = null
    var onPauseChanged: ((Boolean) -> Unit)? = null
    var onVolumeChanged: ((Int) -> Unit)? = null
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
        onVolumeChanged?.invoke(volume)
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
        if (players.isEmpty() && visualizing) player.setFilterFactory(spectrumFilter())
        players.add(player)
    }

    @Synchronized fun detach(player: AudioPlayer) {
        val primary = players.firstOrNull() === player
        players.remove(player)
        if (primary && visualizing) players.firstOrNull()?.setFilterFactory(spectrumFilter())
    }

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
