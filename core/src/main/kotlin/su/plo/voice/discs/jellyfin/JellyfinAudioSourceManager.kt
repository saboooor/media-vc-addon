package su.plo.voice.discs.jellyfin

import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.*
import su.plo.voice.lavaplayer.libs.org.apache.http.message.BasicHeader
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
                    configureBuilder { builder ->
                        builder.disableRedirectHandling()
                        builder.setDefaultHeaders(listOf(BasicHeader("Authorization",
                            JellyfinClient.authorization(connection.session.deviceId, connection.session.token))))
                    }
                })
            }
        }!!
        // Only this trusted, fixed Jellyfin route receives the user's header. Never follow references/redirects.
        val url = client.streamUrl(connection.session, grant.mediaId)
        return backend.source.loadItem(manager, AudioReference(url, grant.label)) as? AudioTrack
            ?: throw JellyfinException("Jellyfin did not return a supported audio file.")
    }
    override fun isTrackEncodable(track: AudioTrack) = false
    override fun encodeTrack(track: AudioTrack, output: DataOutput) { throw UnsupportedOperationException() }
    override fun decodeTrack(info: AudioTrackInfo, input: DataInput): AudioTrack? = null
    override fun shutdown() { backends.values.forEach { it.source.shutdown() }; backends.clear() }
}
