package su.plo.voice.discs

import su.plo.voice.discs.jellyfin.JellyfinLibrary
import su.plo.voice.discs.jellyfin.JellyfinAudioSourceManager
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.slib.api.logging.McLoggerFactory
import su.plo.voice.api.logging.DebugLogger
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.source.ServerProximitySource
import su.plo.voice.discs.config.YoutubeClient
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.getValue
import su.plo.voice.discs.utils.extend.getter
import su.plo.voice.discs.utils.extend.getterOrNull
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioItem
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioReference
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeSourceOptions
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Web
import su.plo.voice.lavaplayer.libs.org.apache.http.HttpHost
import su.plo.voice.lavaplayer.libs.org.apache.http.auth.AuthScope
import su.plo.voice.lavaplayer.libs.org.apache.http.auth.UsernamePasswordCredentials
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.BasicCredentialsProvider
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.HttpClientBuilder
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder
import su.plo.voice.discs.audio.PlaybackSpectrumTap
import su.plo.voice.discs.audio.StereoPcmFrameEncoder
import su.plo.voice.discs.audio.QualityYoutubeSourceManager
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.concurrent.fixedRateTimer
import kotlin.time.Duration.Companion.nanoseconds

class PlasmoAudioPlayerManager : PluginKoinComponent {

    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val debugLogger: DebugLogger by getter()
    private val config: AddonConfig by getter()

    private val jellyfinLibrary: JellyfinLibrary by inject()

    private val lavaPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager().apply { setUseSeekGhosting(false) }
    // Track loading stays on the main manager; this manager executes loaded tracks as PCM.
    // Separate configuration prevents concurrent normal/mono playback changing output formats.
    private val stereoPlayerManager = DefaultAudioPlayerManager().apply {
        configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        configuration.isFilterHotSwapEnabled = true
        setUseSeekGhosting(false)
    }
    private val encryption get() = voiceServer?.defaultEncryption

    fun shutdown() {
        lavaPlayerManager.shutdown()
        stereoPlayerManager.shutdown()
    }

    /** Compare sections in the first minute, bounded by twenty wall seconds and one decoder. */
    suspend fun analyzeMixSections(track: AudioTrack): List<su.plo.voice.discs.audio.MixFeatures> {
        if (track.info.isStream || !track.isSeekable) return emptyList()
        val player = stereoPlayerManager.createPlayer()
        val analysis = su.plo.voice.discs.audio.MixAnalysis()
        songEdges(track)
        val sections = mutableListOf<su.plo.voice.discs.audio.MixFeatures>()
        var tap: PlaybackSpectrumTap? = null
        try {
            player.playTrack(track.makeClone())
            kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                var decodedMs = 0L
                var windowStartMs = 0L
                fun capture() {
                    val features = analysis.snapshot()
                    sections += if (windowStartMs == 0L) features else
                        features.copy(mixCueMs = features.beatAnchorMs.coerceAtLeast(windowStartMs))
                }
                while (decodedMs < 60_000 && player.playingTrack != null) {
                    val frame = player.provide()
                    if (frame == null) { delay(2); continue }
                    val reader = tap ?: PlaybackSpectrumTap(frame.format).also { tap = it }
                    reader.accept(frame.data, null, analysis, frame.timecode)
                    decodedMs += 20
                    if (decodedMs - windowStartMs >= 8_000) {
                        capture()
                        analysis.reset()
                        windowStartMs = decodedMs
                    }
                }
                if (decodedMs > windowStartMs) capture()
            }
            return sections.toList()
        } finally { tap?.close(); player.destroy() }
    }

    suspend fun prepareSongEdges(track: AudioTrack) { songEdges(track) }

    private val edgeCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, su.plo.voice.discs.audio.SongEdges>()
    private suspend fun songEdges(track: AudioTrack): su.plo.voice.discs.audio.SongEdges? {
        if (!track.isSeekable || track.info.isStream || track.duration <= 0) return null
        val key = track.identifier to track.duration
        edgeCache[key]?.let { return it }
        val probe = stereoPlayerManager.createPlayer()
        val scan = su.plo.voice.discs.audio.EdgeSilenceScan(track.duration)
        var tap: PlaybackSpectrumTap? = null
        try {
            probe.playTrack(track.makeClone())
            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                while (!scan.headComplete && probe.playingTrack != null) {
                    val frame = probe.provide()
                    if (frame == null) { delay(2); continue }
                    val reader = tap ?: PlaybackSpectrumTap(frame.format).also { tap = it }
                    scan.head(frame.timecode, reader.accept(frame.data, null))
                }
                probe.playingTrack?.position = (track.duration - scan.limitMs).coerceAtLeast(0L)
                tap?.close(); tap = null
                while (probe.playingTrack != null) {
                    val frame = probe.provide()
                    if (frame == null) { delay(2); continue }
                    val reader = tap ?: PlaybackSpectrumTap(frame.format).also { tap = it }
                    scan.tail(frame.timecode, reader.accept(frame.data, null))
                }
            }
            val result = scan.result()
            if (edgeCache.size >= 256) edgeCache.clear()
            edgeCache[key] = result
            return result
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        finally { tap?.close(); probe.destroy() }
    }

    fun getYoutubeSourceManager(): YoutubeAudioSourceManager? {
        return lavaPlayerManager.source(QualityYoutubeSourceManager::class.java)
            ?: lavaPlayerManager.source(YoutubeAudioSourceManager::class.java)
    }

    fun startTrackJob(
        track: AudioTrack,
        pvSource: ServerProximitySource<*>? = null,
        distance: Short = 0,
        svcChannel: AudioChannel? = null,
        controls: PlaybackControls? = null,
        pcmChannel: Int? = null
    ): Job {
        require(pvSource != null || svcChannel != null) { "No audio destination provided" }
        return startSharedTrackJob(track, pvSource, null, distance, svcChannel, null,
            controls ?: PlaybackControls(), false, pcmChannel)
    }

    private fun createPlaybackPlayer(
        manager: AudioPlayerManager,
        track: AudioTrack,
        controls: PlaybackControls?,
        pcmChannel: Int? = null,
    ): AudioPlayer {
        val player = manager.createPlayer()
        player.setFrameBufferDuration(500)
        player.addListener(object : AudioEventAdapter() {
            override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
                controls?.fail(exception.message ?: "Audio stream failed")
                plugin.slF4JLogger.error("Disc audio stream failed: ${track.info.title}", exception)
                val msg = exception.message ?: ""
                val cause = exception.cause
                val causeMsg = cause?.message ?: ""
                val isAuthIssue = msg.contains("login", ignoreCase = true) || causeMsg.contains("login", ignoreCase = true) ||
                    msg.contains("bot", ignoreCase = true) || causeMsg.contains("bot", ignoreCase = true) ||
                    cause?.javaClass?.simpleName == "AllClientsFailedException"

                if (isAuthIssue) {
                    plugin.slF4JLogger.warn("===================================================================")
                    plugin.slF4JLogger.warn("YouTube blocked stream loading (requires login or bot verification).")
                    plugin.slF4JLogger.warn("To resolve this, authorize YouTube via OAuth2:")
                    plugin.slF4JLogger.warn("Run '/disc oauth' or set 'use_oauth2 = true' in discs.toml,")
                    plugin.slF4JLogger.warn("then visit https://www.google.com/device and enter the code.")
                    plugin.slF4JLogger.warn("===================================================================")
                }
            }

            override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
                controls?.fail("Audio stream stalled for ${thresholdMs}ms")
                plugin.slF4JLogger.warn("Disc audio stream stalled for ${thresholdMs}ms: ${track.info.title}")
            }
        })
        if (controls != null) track.userData = controls
        try {
            player.playTrack(track)
            controls?.attach(player, pcmChannel)
            return player
        } catch (e: Exception) {
            controls?.detach(player)
            player.destroy()
            throw e
        }
    }

    /** One decoder, one clock, and one encoded frame per channel shared by both backends. */
    fun startStereoTrackJob(
        track: AudioTrack,
        leftPvSource: ServerProximitySource<*>?,
        rightPvSource: ServerProximitySource<*>?,
        distance: Short,
        leftSvcChannel: AudioChannel?,
        rightSvcChannel: AudioChannel?,
        controls: PlaybackControls,
    ): Job {
        require((leftPvSource != null && rightPvSource != null) ||
            (leftSvcChannel != null && rightSvcChannel != null)) { "Both stereo destinations are required" }
        return startSharedTrackJob(track, leftPvSource, rightPvSource, distance,
            leftSvcChannel, rightSvcChannel, controls, true)
    }

    private fun startSharedTrackJob(
        track: AudioTrack,
        primaryPv: ServerProximitySource<*>?,
        secondaryPv: ServerProximitySource<*>?,
        distance: Short,
        primarySvc: AudioChannel?,
        secondarySvc: AudioChannel?,
        controls: PlaybackControls,
        splitStereo: Boolean,
        pcmChannel: Int? = null,
    ): Job = scope.launch(Dispatchers.Default) {
        var player: AudioPlayer? = null
        var leftEncoder: AudioChunkEncoder? = null
        var rightEncoder: AudioChunkEncoder? = null
        var spectrumTap: PlaybackSpectrumTap? = null
        val pvSources = listOfNotNull(primaryPv, secondaryPv)
        val svcChannels = listOfNotNull(primarySvc, secondarySvc)
        val enc = encryption
        var sequence = 0L
        var streaming = false
        fun endStream() {
            if (!streaming) return
            pvSources.forEach { it.sendAudioEnd(sequence, distance) }
            svcChannels.forEach { it.flush() }
            sequence++
            streaming = false
        }
        try {
            val manager = if (splitStereo) stereoPlayerManager else lavaPlayerManager
            val encoder = if (splitStereo) {
                val format = OpusAudioDataFormat(1, 48_000, 960)
                val left = format.createEncoder(manager.configuration).also { leftEncoder = it }
                val right = format.createEncoder(manager.configuration).also { rightEncoder = it }
                StereoPcmFrameEncoder(left, right)
            } else null
            songEdges(track)?.let { controls.setSongEdges(it.startMs, it.endMs) }
            val playbackPlayer = createPlaybackPlayer(manager, track, controls, pcmChannel).also { player = it }
            var nextFrameTime = System.nanoTime()
            while (isActive && controls.failure == null) {
                if (!controls.isStream && controls.duration > 0 && controls.position >= controls.duration) break
                if (controls.paused) {
                    endStream()
                    delay(5)
                    nextFrameTime = System.nanoTime()
                    continue
                }
                val remaining = nextFrameTime - System.nanoTime()
                if (remaining > 0) delay(remaining.nanoseconds)
                // Controls and transmission share a monitor; no fetched frame survives a suspension.
                val sent = synchronized(controls) {
                    if (controls.paused || controls.failure != null) false
                    else playbackPlayer.provide()?.let { frame ->
                        if (controls.visualizing || controls.autoMixAnalysis) {
                            val tap = spectrumTap ?: PlaybackSpectrumTap(frame.format).also { spectrumTap = it }
                            tap.accept(frame.data, controls.spectrum.takeIf { controls.visualizing },
                                controls.mixAnalysis.takeIf { controls.autoMixAnalysis }, controls.position, controls.analysisScale)
                        } else {
                            spectrumTap?.close()
                            spectrumTap = null
                        }
                        val pair = encoder?.encode(frame.data)
                        val primary = pair?.first ?: frame.data
                        val secondary = pair?.second
                        val primaryEncrypted = if (primaryPv != null) enc?.encrypt(primary) ?: primary else null
                        val secondaryEncrypted = if (secondaryPv != null && secondary != null) enc?.encrypt(secondary) ?: secondary else null
                        streaming = true
                        primaryPv?.sendAudioFrame(primaryEncrypted!!, sequence, distance)
                        secondaryPv?.sendAudioFrame(secondaryEncrypted!!, sequence, distance)
                        primarySvc?.send(primary)
                        if (secondary != null) secondarySvc?.send(secondary)
                        controls.frameSent(playbackPlayer, frame.timecode)
                        sequence++
                        true
                    } ?: false
                }
                if (!sent) {
                    if (playbackPlayer.playingTrack == null) break
                    delay(5)
                    nextFrameTime = System.nanoTime()
                    continue
                }
                nextFrameTime = maxOf(nextFrameTime + 20_000_000L, System.nanoTime())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            controls.fail(e.message ?: "Audio playback failed")
            plugin.slF4JLogger.error("Disc playback failed: ${track.info.title}", e)
        } finally {
            fun cleanup(action: () -> Unit) {
                runCatching(action).onFailure { plugin.slF4JLogger.warn("Playback cleanup failed", it) }
            }
            pvSources.forEach { source -> cleanup { source.sendAudioEnd(sequence, distance) } }
            svcChannels.forEach { channel -> cleanup { channel.flush() } }
            spectrumTap?.let { tap -> cleanup { tap.close() } }
            player?.let { playbackPlayer -> controls.detach(playbackPlayer); cleanup { playbackPlayer.destroy() } }
            leftEncoder?.let { cleanup { it.close() } }
            rightEncoder?.let { cleanup { it.close() } }
            pvSources.forEach { source -> cleanup { source.remove() } }
        }
    }

    val noMatchesException = FriendlyException(
        "No matches",
        FriendlyException.Severity.COMMON,
        Exception("No matches")
    )

    fun getTrack(identifier: String): CompletableFuture<AudioTrack> {

        val future = CompletableFuture<AudioTrack>()

        lavaPlayerManager.loadItem(identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                if (playlist.selectedTrack == null) {
                    future.completeExceptionally(noMatchesException)
                    return
                }

                future.complete(playlist.selectedTrack)
            }

            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }

            override fun noMatches() {
                future.completeExceptionally(noMatchesException)
            }
        })
        return future
    }

    fun getPlaylist(identifier: String): CompletableFuture<AudioPlaylist> {

        val future = CompletableFuture<AudioPlaylist>()

        lavaPlayerManager.loadItem(identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.completeExceptionally(noMatchesException)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                future.complete(playlist)
            }

            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }

            override fun noMatches() {
                future.completeExceptionally(noMatchesException)
            }
        })

        return future
    }

    private fun httpProxy(): Pair<HttpHost, BasicCredentialsProvider?>? {
        val proxyString = config.httpProxy ?: return null

        val regex =
            Regex("^(?:(https?)://)?(?:(\\w+):(\\w*)@)?([a-zA-Z0-9][a-zA-Z0-9-_.]{0,61}|(\\d{1,3}(?:\\.\\d{1,3}){3})):(\\d{1,5})\$")
        val match = regex.find(proxyString) ?: run {
            plugin.slF4JLogger.error("Failed to parse proxy: {}", proxyString)
            return null
        }

        val scheme = match.groupValues[1].takeIf { it.isNotBlank() }
        val username = match.groupValues[2]
        val password = match.groupValues[3]
        val hostname = match.groupValues[4]
        val port = match.groupValues[6].toInt()

        val credentials =
            if (username.isNotBlank() && password.isNotBlank()) {
                BasicCredentialsProvider().apply {
                    setCredentials(
                        AuthScope.ANY,
                        UsernamePasswordCredentials(username, password)
                    )
                }
            } else null

        val host = HttpHost(hostname, port, scheme)

        return host to credentials
    }

    private fun saveToken() {
        lavaPlayerManager.sourceManagers
            .filterIsInstance<YoutubeAudioSourceManager>()
            .firstOrNull()
            ?.takeIf { it.oauth2RefreshToken != null }
            ?.let {
                val refreshTokenFile = File(plugin.dataFolder, ".youtube-token")
                refreshTokenFile.writeText(it.oauth2RefreshToken!!)
                plugin.slF4JLogger.info("YouTube oauth2 refresh token saved to .youtube-token")
            }
    }

    private fun listenForTokenChange(youtubeSourceManager: YoutubeAudioSourceManager) {
        val currentToken = youtubeSourceManager.oauth2RefreshToken
        fixedRateTimer(
            "[vc-addon-discs] [youtube-refresh-token-listener]",
            true,
            5000L,
            5000L,
        ) {
            val newToken = youtubeSourceManager.oauth2RefreshToken ?: return@fixedRateTimer
            if (currentToken == newToken) return@fixedRateTimer

            saveToken()
            cancel()
        }
    }

    fun startOauth2(force: Boolean = false): Boolean {
        val ytSource = lavaPlayerManager.sourceManagers
            .filterIsInstance<YoutubeAudioSourceManager>()
            .firstOrNull() ?: return false

        val tokenFile = File(plugin.dataFolder, ".youtube-token")
        if (force && tokenFile.exists()) {
            tokenFile.delete()
        }

        val refreshToken = if (force) null else {
            tokenFile.takeIf { it.isFile && it.exists() }?.readText()?.trim()
        }

        ytSource.useOauth2(refreshToken, false)
        listenForTokenChange(ytSource)
        return true
    }

    private fun proxyHttpBuilder(): Consumer<HttpClientBuilder>? {
        val (host, credentials) = httpProxy() ?: return null

        return Consumer { builder ->
            builder.setProxy(host)
            credentials?.let {
                builder.setDefaultCredentialsProvider(it)
            }
        }
    }

    fun registerSources() {
        lavaPlayerManager.configuration.isFilterHotSwapEnabled = true
        val proxyHttpBuilder = proxyHttpBuilder()
        proxyHttpBuilder?.let { lavaPlayerManager.setHttpBuilderConfigurator(it) }

        lavaPlayerManager.registerSourceManagerCatching("youtube") {
            val youtubeClients = config.youtubeSource.clients
                ?.mapNotNull {
                    try {
                        // todo: config resolver don't support list of enums for some reason
                        YoutubeClient.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(
                    YoutubeClient.MUSIC,
                    YoutubeClient.ANDROID_VR,
                    YoutubeClient.WEB,
                    YoutubeClient.WEBEMBEDDED,
                    YoutubeClient.TV,
                    YoutubeClient.TVHTML5_SIMPLY,
                )
            plugin.slF4JLogger.info("YouTube clients: {}", youtubeClients)

            val options = YoutubeSourceOptions()
            config.youtubeSource.remoteCipher?.let { remoteCipher ->
                plugin.slF4JLogger.info("YouTube remote cipher url: {}", remoteCipher.url)
                options.setRemoteCipher(remoteCipher.url, remoteCipher.password, "yt-source")
            }

            QualityYoutubeSourceManager(options, { debugLogger.log(it) }, *youtubeClients.map { it.client.get() }.toTypedArray())
                .also { source ->
                    proxyHttpBuilder?.let { source.httpInterfaceManager.configureBuilder(it) }

                    val poToken = config.youtubeSource.poToken
                    val refreshTokenFile = File(plugin.dataFolder, ".youtube-token")
                    val refreshToken = refreshTokenFile
                        .takeIf { it.isFile && it.exists() }
                        ?.readText()
                        ?.trim()

                    if (poToken != null) {
                        Web.setPoTokenAndVisitorData(poToken.token, poToken.visitorData)
                    } else if (config.youtubeSource.useOauth2 || refreshToken != null) {
                        source.useOauth2(refreshToken, false)
                        if (refreshToken == null) listenForTokenChange(source)
                    }
                }
        }

        lavaPlayerManager.registerSourceManagerCatching("soundcloud") { SoundCloudAudioSourceManager.createDefault() }
        lavaPlayerManager.registerSourceManagerCatching("bandcamp") { BandcampAudioSourceManager() }
        lavaPlayerManager.registerSourceManagerCatching("vimeo") { VimeoAudioSourceManager() }
        lavaPlayerManager.registerSourceManagerCatching("twitch") { TwitchStreamAudioSourceManager() }
        lavaPlayerManager.registerSourceManagerCatching("beam.pro") { BeamAudioSourceManager() }
        lavaPlayerManager.registerSourceManagerCatching("getyarn.io") { GetyarnAudioSourceManager() }
        lavaPlayerManager.registerSourceManagerCatching("jellyfin") { JellyfinAudioSourceManager(jellyfinLibrary) }
        lavaPlayerManager.registerSourceManagerCatching("http") { CustomHttpAudioSourceManager() }

        if (config.localSource.enabled) {
            val rootPath = config.localSource.path.takeIf { it.isNotEmpty() }
                ?.let { Path.of(it) }
                ?: plugin.dataFolder.toPath().resolve("local").also { it.toFile().mkdirs() }
            lavaPlayerManager.registerSourceManagerCatching("local") { CustomLocalAudioSourceManager(rootPath) }
        }
    }

    private fun AudioPlayerManager.registerSourceManagerCatching(
        sourceName: String,
        sourceManager: () -> AudioSourceManager,
    ) {
        try {
            registerSourceManager(sourceManager.invoke())
        } catch (e: Throwable) {
            logger.error("Failed to register $sourceName source", e)
        }
    }

    inner class CustomLocalAudioSourceManager(
        private val rootPath: Path,
    ) : LocalAudioSourceManager() {
        override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
            val identifier = reference.identifier
            if (!identifier.startsWith("file://") && !identifier.startsWith("local://"))
                return null

            val path = rootPath.resolve(identifier.removePrefix("file://").removePrefix("local://"))
            if (!path.startsWith(rootPath)) return null

            val file = path.toFile()
                .takeIf { it.exists() && it.isFile() && it.canRead() }
                ?: return null

            val newReference = AudioReference(file.toString(), reference.title, reference.containerDescriptor)

            return super.loadItem(manager, newReference)
        }
    }

    inner class CustomHttpAudioSourceManager : HttpAudioSourceManager() {
        override fun loadItem(manager: AudioPlayerManager?, reference: AudioReference?): AudioItem? {
            if (reference != null && config.httpSource.whitelistEnabled) {
                val identifier = reference.identifier
                val host = runCatching { URI(identifier) }.getOrNull()?.host ?: return null
                val hostSplit = host.split(".")
                if (!config.httpSource.whitelist.any {
                        val itemSplitLength = it.split(".").size

                        val hostToCompare = hostSplit
                            .subList((hostSplit.size - itemSplitLength).coerceAtLeast(0), hostSplit.size)
                            .joinToString(".")

                        hostToCompare == it
                    }) return null
            }
            return super.loadItem(manager, reference)
        }
    }

    companion object {
        private val logger = McLoggerFactory.createLogger("PlasmoAudioPlayerManager")
    }
}


