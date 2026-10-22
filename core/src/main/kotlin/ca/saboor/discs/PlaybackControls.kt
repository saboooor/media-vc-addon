package ca.saboor.discs

import ca.saboor.discs.audio.AudioFilterChain
import ca.saboor.discs.audio.AudioFilterConfig
import ca.saboor.discs.jellyfin.JellyfinTranscodedTrack
import ca.saboor.discs.visualizer.SpectrumAnalyzer
import java.util.concurrent.CopyOnWriteArrayList
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer

/** One logical playback, shared by the PV and SVC decoders. */
class PlaybackControls(
    initialVolume: Int = 100,
    initialGain: Double = 1.0,
    initialPositionMs: Long = 0L,
    initialFilterConfig: AudioFilterConfig = AudioFilterConfig()
) {
    @Volatile var failure: String? = null
        private set
    @Synchronized fun fail(message: String) {
        if (failure == null) failure = message
    }
    val spectrum = SpectrumAnalyzer()
    private val timescaleClock = ca.saboor.discs.audio.TimescaleClock()
    @Volatile var mixTempoFactor = 1.0
        private set
    fun setMixTempoFactor(value: Double) {
        require(value.isFinite())
        val wasActive = mixTempoFactor != 1.0
        mixTempoFactor = value.coerceIn(0.92, 1.08)
        if (!autoMixAnalysis && wasActive != (mixTempoFactor != 1.0)) refreshTimescaleFilters()
    }
    val playbackRate: Double get() = filterConfig.speed * filterConfig.tempo * mixTempoFactor
    val mixAnalysis = ca.saboor.discs.audio.MixAnalysis(scanPastUncertainSections = true)
    @Volatile var autoMixAnalysis = false
        set(value) { if (field != value) { field = value; refreshTimescaleFilters() } }
    @Synchronized private fun refreshTimescaleFilters() { players.forEach { updateFilter(it) } }
    @Volatile var sentAudioMs = 0L
        private set
    @Volatile var mixBassGain = 1.0
        private set
    fun setMixBassGain(value: Double) { mixBassGain = value.coerceIn(0.0, 1.0) }
    @Volatile private var mixLowPassHz = 0.0
    @Volatile private var mixHighPassHz = 0.0
    fun setMixPasses(lowHz: Double = 0.0, highHz: Double = 0.0) {
        mixLowPassHz = lowHz; mixHighPassHz = highHz
    }
    val currentGain: Double get() = gain
    val analysisScale: Double get() = if (volume * gain > 0.01) 100.0 / (volume * gain) else 1.0
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
    @Volatile var filterConfig: AudioFilterConfig = initialFilterConfig
        private set

    @Synchronized fun applyFilterConfig(config: AudioFilterConfig) {
        filterConfig = config
        players.forEach { updateFilter(it) }
        transitionPartner?.applyFilterConfig(config)
    }

    private fun updateFilter(player: AudioPlayer) {
        val channel = channels[player]
        player.setFilterFactory(AudioFilterChain.buildFactory(filterConfig, channel, onSeek = { spectrum.reset() },
            timescale = if (autoMixAnalysis || mixTempoFactor != 1.0 || filterConfig.tempo != 1.0 ||
                filterConfig.speed != 1.0 || filterConfig.pitchSemitones != 0.0) ({ ca.saboor.discs.audio.TimescaleParameters(
                filterConfig.tempo * mixTempoFactor, filterConfig.pitchSemitones, filterConfig.speed) }) else null, onTimescaleOutput = timescaleClock::decoded,
            mixBass = if (autoMixAnalysis) ({ mixBassGain }) else null,
            mixLowPass = if (autoMixAnalysis) ({ mixLowPassHz }) else null,
            mixHighPass = if (autoMixAnalysis) ({ mixHighPassHz }) else null))
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
    /** A confident, beat-aligned transition to the prepared next track is approaching. */
    @Volatile var autoMixStatus: String? = null
    @Volatile var goodAutoMixUpcoming = false
    val displayTitle: String get() = title + if (autoMixAnalysis && goodAutoMixUpcoming) " ✨" else ""
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
        sentAudioMs += 20
        val rawPosition = if (track is JellyfinTranscodedTrack) track.absoluteFramePosition(timecodeMs) else timecodeMs
        transmittedPositionMs = timescaleClock.sent(rawPosition)
    }
    val position: Long get() = transmittedPositionMs ?: players.firstOrNull()?.playingTrack?.position?.takeIf { it > 0 } ?: initialPositionMs
    @Volatile private var trimmedEndMs: Long? = null
    @Volatile private var trimmedStartMs = 0L
    internal fun setSongEdges(startMs: Long, endMs: Long) {
        trimmedStartMs = startMs
        trimmedEndMs = endMs
        initialPositionMs = maxOf(initialPositionMs, startMs).coerceAtMost((endMs - 1).coerceAtLeast(0))
        timescaleClock.reset(initialPositionMs)
    }
    val duration: Long get() = (players.firstOrNull()?.playingTrack?.duration ?: 0).let { raw ->
        trimmedEndMs?.let { minOf(raw, it) } ?: raw
    }
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
        val target = positionMs.coerceIn(trimmedStartMs.coerceAtMost((duration - 1).coerceAtLeast(0)), (duration - 1).coerceAtLeast(0))
        transmittedPositionMs = null
        timescaleClock.reset(target)
        initialPositionMs = target
        players.forEach { seekPlayer(it, target) }
        spectrum.reset()
        mixAnalysis.reset()
        onSeek?.invoke(target)
    }
}

