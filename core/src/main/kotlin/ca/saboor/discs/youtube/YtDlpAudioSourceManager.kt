package ca.saboor.discs.youtube

import ca.saboor.discs.PlaybackControls
import ca.saboor.discs.screen.VideoSourceResolver
import java.io.DataInput
import java.io.DataOutput
import java.net.URI
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.*
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import su.plo.voice.lavaplayer.libs.org.apache.http.message.BasicHeader

/** Opt-in replacement: metadata and playback both use yt-dlp, including flat playlist entries. */
internal class YtDlpAudioSourceManager(
    private val extractor: YtDlpClient,
    private val configureHttp: java.util.function.Consumer<su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.HttpClientBuilder>? = null,
) : AudioSourceManager {
    override fun getSourceName() = "youtube-ytdlp"
    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier
        if (!accepts(identifier)) return null
        val id = VideoSourceResolver.extractYouTubeVideoId(identifier)
        val search = identifier.startsWith("ytsearch:") || identifier.startsWith("ytmsearch:")
        val playlist = runCatching { URI(identifier).rawQuery.orEmpty().split('&').any { it.startsWith("list=") } }.getOrDefault(false)
        if (id != null && !search && !playlist) return Track(extractor.audio(id), manager)
        val (title, media) = extractor.playlist(identifier)
        val tracks = media.map { Track(it, manager) }
        return BasicAudioPlaylist(title, tracks, tracks.firstOrNull { it.identifier == id }, search)
    }

    private inner class Track(private val media: YtDlpClient.Media, private val manager: AudioPlayerManager) :
        DelegatedAudioTrack(AudioTrackInfo(media.title, media.author, media.durationMs, media.id, media.live, media.watchUrl)) {
        override fun getSourceManager() = this@YtDlpAudioSourceManager
        override fun isSeekable() = !media.live && media.durationMs > 0
        override fun getDuration() = media.durationMs
        override fun process(executor: LocalAudioTrackExecutor) {
            // Resolve again on playback/cloning: cached URLs are short-lived and expire safely.
            val resolved = extractor.audio(media.id)
            getUserData(PlaybackControls::class.java)?.let {
                it.sourceAvailableFormats = resolved.format
                it.sourceFormatDescription = "${resolved.format} · yt-dlp"
            }
            val http = HttpAudioSourceManager()
            try {
                http.configureBuilder { builder ->
                    configureHttp?.accept(builder)
                    builder.setDefaultHeaders(resolved.headers.map { BasicHeader(it.key, it.value) })
                }
                val delegate = http.loadItem(manager, AudioReference(resolved.url!!, media.title)) as? InternalAudioTrack
                    ?: error("yt-dlp returned an unsupported audio format")
                processDelegate(delegate, executor)
            } finally { http.shutdown() }
        }
        override fun makeShallowClone(): AudioTrack = Track(media, manager)
    }

    override fun isTrackEncodable(track: AudioTrack) = false
    override fun encodeTrack(track: AudioTrack, output: DataOutput) { throw UnsupportedOperationException() }
    override fun decodeTrack(info: AudioTrackInfo, input: DataInput): AudioTrack? = null
    override fun shutdown() = extractor.close()

    companion object {
        internal fun accepts(identifier: String): Boolean {
            if (identifier.matches(Regex("[A-Za-z0-9_-]{11}"))) return true
            if (identifier.startsWith("ytsearch:") || identifier.startsWith("ytmsearch:")) return true
            val uri = runCatching { URI(identifier) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme in listOf("http", "https") &&
                (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be")
        }
    }
}
