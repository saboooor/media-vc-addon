package su.plo.voice.discs.audio

import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.format.StreamFormat
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.format.TrackFormats

enum class YoutubeSourceQuality(val label: String, val maxBitrate: Long?) {
    BEST("Best available", null),
    BALANCED("Balanced (up to 128 kbps)", 128_000),
    LOW("Data saver (up to 64 kbps)", 64_000);

    fun select(formats: TrackFormats): StreamFormat {
        if (maxBitrate == null) return formats.bestFormat
        val audio = formats.formats.filter {
            it.info?.mimeType?.startsWith("audio/") == true && it.isDefaultAudioTrack &&
                it.audioChannels in 1..2 && it.bitrate > 0
        }
        if (audio.isEmpty()) return formats.bestFormat
        val clean = audio.filterNot { it.isDrc }.ifEmpty { audio }
        val withinLimit = clean.filter { it.bitrate <= maxBitrate }
        return if (withinLimit.isNotEmpty()) TrackFormats(withinLimit, formats.playerScriptUrl).bestFormat
        else clean.minBy { it.bitrate }
    }
}
