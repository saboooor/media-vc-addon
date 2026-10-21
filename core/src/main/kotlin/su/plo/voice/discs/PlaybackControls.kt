package su.plo.voice.discs

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import java.util.concurrent.CopyOnWriteArrayList
import su.plo.voice.discs.visualizer.SpectrumAnalyzer
import su.plo.voice.discs.jellyfin.JellyfinTranscodedTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory

/** One logical playback, shared by the PV and SVC decoders. */
class PlaybackControls(
    initialVolume: Int = 100,
    initialGain: Double = 1.0,
    initialPositionMs: Long = 0L
) {
    @Volatile var failure: String? = null
        private set
    @Synchronized fun fail(message: String) {
        if (failure == null) failure = message
    }
    val spectrum = SpectrumAnalyzer()
    @Volatile var visualizing = false
        private set
    private val analysisConsumers = mutableSetOf<String>()
    @Synchronized fun setVisualizing(enabled: Boolean, consumer: String = "visualizer") {
        if (enabled) analysisConsumers.add(consumer) else analysisConsumers.remove(consumer)
        val active = analysisConsumers.isNotEmpty()
        if (visualizing == active) return
        visualizing = active
        spectrum.reset()
    }
    private val channels = java.util.concurrent.ConcurrentHashMap<AudioPlayer, Int>()
    private fun updateFilter(player: AudioPlayer) {
        val channel = channels[player]
        player.setFilterFactory(if (channel != null)
            channelFilter(channel) else null)
    }
    private fun channelFilter(channel: Int) = PcmFilterFactory { _, _, output ->
        listOf(object : FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                SpeakerChannels.isolate(input, offset, length, channel)
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
    @Volatile var initialPositionMs: Long = initialPositionMs
        private set
    /** Position of audio actually transmitted, unavailable while a new stream/seek buffers. */
    @Volatile var transmittedPositionMs: Long? = null
        private set
    @Synchronized internal fun frameSent(player: AudioPlayer, timecodeMs: Long) {
        if (player !in players) return
        val track = player.playingTrack ?: return
        transmittedPositionMs = if (track is JellyfinTranscodedTrack) track.absoluteFramePosition(timecodeMs) else timecodeMs
    }
    val position: Long get() = players.firstOrNull()?.playingTrack?.position?.takeIf { it > 0 } ?: initialPositionMs
    val duration: Long get() = players.firstOrNull()?.playingTrack?.duration ?: 0
    val isStream: Boolean get() = players.firstOrNull()?.playingTrack?.let {
        it.info.isStream && (it as? JellyfinTranscodedTrack)?.canRestart != true
    } == true
    val seekable: Boolean get() = players.firstOrNull()?.playingTrack?.let {
        it.isSeekable || (it as? JellyfinTranscodedTrack)?.canRestart == true
    } == true

    private fun seekPlayer(player: AudioPlayer, position: Long) {
        val track = player.playingTrack ?: return
        if (track is JellyfinTranscodedTrack && track.canRestart) {
            val replacement = track.restartAt(position).also { it.userData = track.userData }
            // Clear LavaPlayer's shadow track so old audio cannot leak into the new seek clock.
            player.stopTrack()
            player.playTrack(replacement)
        } else if (track.isSeekable) track.position = position
    }

    @Synchronized fun attach(player: AudioPlayer, channel: Int? = null) {
        val current = players.firstOrNull()?.playingTrack
        if (current != null) {
            seekPlayer(player, current.position)
        } else if (initialPositionMs > 0L) {
            val track = player.playingTrack
            val target = if (track != null && track.duration > 0 && initialPositionMs >= track.duration - 1000) 0L else initialPositionMs
            if (target > 0L) seekPlayer(player, target)
        }
        player.isPaused = paused
        player.volume = effectiveVolume()
        if (channel != null) channels[player] = channel
        players.add(player)
        updateFilter(player)
    }

    @Synchronized fun detach(player: AudioPlayer) {
        players.remove(player)
        channels.remove(player)
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
        transmittedPositionMs = null
        initialPositionMs = target
        players.forEach { seekPlayer(it, target) }
        spectrum.reset()
        onSeek?.invoke(target)
    }
}

