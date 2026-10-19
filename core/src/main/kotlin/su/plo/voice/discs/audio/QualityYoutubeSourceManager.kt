package su.plo.voice.discs.audio

import su.plo.voice.discs.PlaybackControls
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeSourceOptions
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.skeleton.Client
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.YoutubeAudioTrack
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.format.StreamFormat
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.track.format.TrackFormats

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.ClientOptions
import java.net.URI

/** Changes selection before YouTube URL deciphering; keeps the normal client and OAuth fallback paths. */
class QualityYoutubeSourceManager(options: YoutubeSourceOptions, private val logFormats: (String) -> Unit, vararg clients: Client) :
    YoutubeAudioSourceManager(options, *clients) {
    private val playbackContext = ThreadLocal<PlaybackControls?>()

    override fun buildAudioTrack(info: AudioTrackInfo): YoutubeAudioTrack = QualityTrack(info)

    override fun getClients(): Array<Client> {
        val controls = playbackContext.get() ?: return super.getClients()
        val quality = YoutubeSourceQuality.BEST
        return super.getClients().map { client ->
            object : Client by client {
                override fun supportsOAuth(): Boolean = client.supportsOAuth()
                override fun supportsFormatLoading(): Boolean = client.supportsFormatLoading()
                override fun isEmbedded(): Boolean = client.isEmbedded
                override fun requirePlayerScript(): Boolean = client.requirePlayerScript()
                override fun getOptions(): ClientOptions = client.options
                override fun getPlayabilityStatus(response: JsonBrowser, isPlayback: Boolean): Client.PlayabilityStatus =
                    client.getPlayabilityStatus(response, isPlayback)
                override fun getUnplayableReason(response: JsonBrowser): String? =
                    client.getUnplayableReason(response)
                override fun findSelectedTrack(tracks: MutableList<AudioTrack>, trackId: String): AudioTrack? =
                    client.findSelectedTrack(tracks, trackId)
                override fun transformPlaybackUri(playbackUri: URI, uri: URI): URI =
                    client.transformPlaybackUri(playbackUri, uri)
                override fun buildAudioTrack(
                    source: YoutubeAudioSourceManager,
                    json: JsonBrowser,
                    videoId: String,
                    title: String,
                    duration: Long,
                    uploader: String,
                    isStream: Boolean
                ): AudioTrack = client.buildAudioTrack(source, json, videoId, title, duration, uploader, isStream)

                override fun loadFormats(source: YoutubeAudioSourceManager, http: HttpInterface, videoId: String): TrackFormats {
                    val formats = client.loadFormats(source, http, videoId)
                    val offered = formats.formats.filter { it.info?.mimeType?.startsWith("audio/") == true }
                    val summary = offered.joinToString("; ") {
                        "itag=${it.itag} codec=${it.info?.codec} bitrate=${it.bitrate} channels=${it.audioChannels} default=${it.isDefaultAudioTrack} drc=${it.isDrc}"
                    }
                    controls.sourceAvailableFormats = offered.filter { it.isDefaultAudioTrack }
                        .map { "${it.bitrate / 1000} kbps ${it.info?.codec ?: "audio"}" }.distinct().joinToString(", ")
                    logFormats("YouTube formats: video=$videoId client=${client.identifier} quality=${quality.name} offered=[$summary]")
                    return object : TrackFormats(formats.formats, formats.playerScriptUrl) {
                        override fun getBestFormat(): StreamFormat {
                            val selected = quality.select(formats)
                            controls.sourceFormatDescription = "${selected.bitrate / 1000} kbps · ${selected.info?.codec ?: "audio"} · ${client.identifier} · itag ${selected.itag}"
                            logFormats("YouTube selected: video=$videoId client=${client.identifier} quality=${quality.name} itag=${selected.itag} codec=${selected.info?.codec} bitrate=${selected.bitrate}")
                            return selected
                        }
                    }
                }
            }
        }.toTypedArray()
    }

    private inner class QualityTrack(info: AudioTrackInfo) : YoutubeAudioTrack(info, this@QualityYoutubeSourceManager) {
        override fun process(executor: LocalAudioTrackExecutor) {
            playbackContext.set(getUserData(PlaybackControls::class.java))
            try { super.process(executor) } finally { playbackContext.remove() }
        }
        override fun makeShallowClone(): AudioTrack = QualityTrack(info)
    }
}
