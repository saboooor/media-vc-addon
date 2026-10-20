package su.plo.voice.discs

import de.maxhenkel.voicechat.api.audiochannel.AudioChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.slib.api.logging.McLoggerFactory
import su.plo.voice.api.logging.DebugLogger
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.provider.AudioFrameProvider
import su.plo.voice.api.server.audio.provider.AudioFrameResult
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
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrackState
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeSourceOptions
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Web
import su.plo.voice.lavaplayer.libs.org.apache.http.HttpHost
import su.plo.voice.lavaplayer.libs.org.apache.http.auth.AuthScope
import su.plo.voice.lavaplayer.libs.org.apache.http.auth.UsernamePasswordCredentials
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.BasicCredentialsProvider
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.HttpClientBuilder
import su.plo.voice.discs.audio.QualityYoutubeSourceManager
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.concurrent.fixedRateTimer

class PlasmoAudioPlayerManager : PluginKoinComponent {

    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val debugLogger: DebugLogger by getter()
    private val config: AddonConfig by getter()

    private val lavaPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager()
    private val encryption get() = voiceServer?.defaultEncryption

    fun shutdown() {
        lavaPlayerManager.shutdown()
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
        if (pvSource != null && svcChannel != null) {
            // Keep PV's sender lifecycle (including source state) intact. Each backend
            // consumes its own track so neither can steal frames from the other.
            val svcTrack = track.makeClone().apply { position = track.position }
            return scope.launch {
                var pvJob: Job? = null
                var svcJob: Job? = null
                try {
                    pvJob = startTrackJob(track, pvSource, distance, controls = controls, pcmChannel = pcmChannel)
                    svcJob = startTrackJob(svcTrack, svcChannel = svcChannel, controls = controls, pcmChannel = pcmChannel)
                    svcJob.join()
                } finally {
                    withContext(NonCancellable) {
                        svcJob?.cancelAndJoin()
                        pvJob?.cancelAndJoin()
                    }
                }
            }
        }

        val player = lavaPlayerManager.createPlayer()
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
        player.playTrack(track)
        controls?.attach(player, pcmChannel)

        val trackTitle = pvSource?.sourceInfo?.name ?: track.info.title
        debugLogger.log("Starting track \"$trackTitle\" on pvSource=$pvSource svcChannel=$svcChannel")

        if (pvSource != null) {
            val enc = encryption
            val frameProvider = object : AudioFrameProvider {
                override fun provide20ms(): AudioFrameResult =
                    if (
                        controls?.failure != null || track.state == AudioTrackState.FINISHED ||
                        (track.state == AudioTrackState.INACTIVE && track.position > 0)
                    ) {
                        AudioFrameResult.Finished
                    } else {
                        val audioFrame = player.provide()
                        val rawData = audioFrame?.data
                        val frame = rawData?.let {
                            enc?.encrypt(it) ?: it
                        }

                        AudioFrameResult.Provided(frame)
                    }
            }

            val sender = pvSource.createAudioSender(frameProvider, distance)
                .also { it.start() }
            val job = sender.job ?: throw IllegalStateException("AudioSender job is not started")
            sender.onStop {
            controls?.detach(player)
                player.destroy()
                pvSource.remove()
            }

            return job
        } else if (svcChannel != null) {
            val job = scope.launch(Dispatchers.Default) {
                try {
                    var nextTime = System.nanoTime()
                    while (isActive) {
                        if (
                            controls?.failure != null || track.state == AudioTrackState.FINISHED ||
                            (track.state == AudioTrackState.INACTIVE && track.position > 0)
                        ) {
                            break
                        }

                        val audioFrame = player.provide(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (audioFrame != null) {
                            svcChannel.send(audioFrame.data)
                        }

                        nextTime += 20_000_000L
                        val sleepTime = (nextTime - System.nanoTime()) / 1_000_000L
                        if (sleepTime > 0) {
                            delay(sleepTime)
                        } else if (sleepTime < -100L) {
                            nextTime = System.nanoTime()
                        }
                    }
                } finally {
                    try {
                        svcChannel.flush()
                    } finally {
                        controls?.detach(player)
                        player.destroy()
                    }
                }
            }
            return job
        } else {
            controls?.detach(player)
            player.destroy()
            throw IllegalStateException("Neither Plasmo Voice nor Simple Voice Chat audio destination provided")
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

