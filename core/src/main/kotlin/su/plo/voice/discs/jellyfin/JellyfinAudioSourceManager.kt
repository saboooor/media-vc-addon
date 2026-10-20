package su.plo.voice.discs.jellyfin

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.*
import su.plo.voice.lavaplayer.libs.org.apache.http.message.BasicHeader
import su.plo.voice.lavaplayer.libs.org.apache.http.client.config.RequestConfig
import java.net.SocketTimeoutException
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JellyfinAudioSourceManager(private val library: JellyfinLibrary) : AudioSourceManager {
    private class Backend(val session: JellyfinClient.Session, val source: HttpAudioSourceManager)
    private val client = JellyfinClient()
    private val backends = ConcurrentHashMap<UUID, Backend>()
    override fun getSourceName() = "jellyfin"
    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        if (!reference.identifier.startsWith("jellyfin:")) return null
        val (connection, grant) = library.resolve(reference.identifier)
        val backend = backends.compute(connection.id) { _, old ->
            if (old?.session === connection.session) old else {
                old?.source?.shutdown()
                Backend(connection.session, HttpAudioSourceManager().apply {
                    // Jellyfin may need to start FFmpeg before sending response headers.
                    configureRequests { request -> RequestConfig.copy(request)
                        .setConnectTimeout(10_000)
                        .setConnectionRequestTimeout(10_000)
                        .setSocketTimeout(60_000)
                        .build()
                    }
                    configureBuilder { builder ->
                        builder.disableRedirectHandling()
                        builder.setDefaultHeaders(listOf(BasicHeader("Authorization",
                            JellyfinClient.authorization(connection.session.deviceId, connection.session.token))))
                    }
                })
            }
        }!!
        // Only this trusted, fixed Jellyfin route receives the user's header. Never follow references/redirects.
        val url = client.streamUrl(connection.session, grant.mediaId, grant.type ?: "Audio")
        return try {
            val track = backend.source.loadItem(manager, AudioReference(url, grant.label)) as? AudioTrack
                ?: throw JellyfinException("Jellyfin did not return a supported audio file.")
            if ((grant.type ?: "Audio") in listOf("Audio", "AudioBook")) track
            else JellyfinTranscodedTrack(track as InternalAudioTrack, grant.durationMs)
        } catch (e: FriendlyException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is SocketTimeoutException }) {
                throw FriendlyException("Jellyfin took too long to start the audio stream. Check the Jellyfin server and its transcoding logs, then try again.",
                    FriendlyException.Severity.COMMON, e)
            }
            throw e
        }
    }
    override fun isTrackEncodable(track: AudioTrack) = false
    override fun encodeTrack(track: AudioTrack, output: DataOutput) { throw UnsupportedOperationException() }
    override fun decodeTrack(info: AudioTrackInfo, input: DataInput): AudioTrack? = null
    override fun shutdown() { backends.values.forEach { it.source.shutdown() }; backends.clear() }
}
