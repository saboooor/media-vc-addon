package su.plo.voice.discs

import ca.saboor.discs.bridge.VoiceBridgeCompat
import ca.saboor.discs.svc.SvcDiscsPlugin
import ca.saboor.discs.utils.extend.cleanTrackName
import ca.saboor.discs.utils.extend.suspendSyncFor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.chat.style.McTextStyle
import su.plo.voice.api.logging.DebugLogger
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.discs.api.PlayTrackFromGoatHornEvent
import su.plo.voice.discs.utils.HornCancellationCause
import su.plo.voice.discs.utils.HornReplaceCause
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack

class GoatHornManager : PluginKoinComponent {

    private val config: AddonConfig by getter()
    private val plugin: JavaPlugin by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val scope: CoroutineScope by inject()
    private val audioPlayerManager: PlasmoAudioPlayerManager by getter()
    private val debugLogger: DebugLogger by getter()
    private val sourceLine: ServerSourceLine? by getterOrNull()
    private val svcPlugin: SvcDiscsPlugin? by getterOrNull()

    private val jobByPlayer: MutableMap<Player, Job> = ConcurrentHashMap()

    fun canPlay(track: AudioTrack): Boolean {
        val maxDurationSeconds = config.goatHorn.maxDurationSeconds
        return maxDurationSeconds <= 0 || ((track.info.length / 1000) <= maxDurationSeconds) && !track.info.isStream
    }

    fun playTrack(
        player: Player,
        item: ItemStack
    ) {
        val identifier = item.identifier() ?: return

        scope.launch {
            cancelTrack(player, HornReplaceCause())?.join()
            jobByPlayer[player] = startJob(player, identifier, item)
        }
        Unit
    }

    fun cancelTrack(player: Player, cause: HornCancellationCause): Job? =
        jobByPlayer.remove(player)
            ?.also { it.cancel(cause) }

    private fun startJob(
        player: Player,
        identifier: String,
        item: ItemStack
    ): Job = scope.launch {
        val isPvPlayer = (svcPlugin?.hasClient(player.uniqueId) != true && VoiceBridgeCompat.isPlayerListeningOnPv(player.uniqueId, voiceServer))
        val voicePlayer = if (isPvPlayer) voiceServer?.let { player.asVoicePlayer(it) } else null

        if (voicePlayer != null) {
            voicePlayer.instance.sendActionBar(
                McTextComponent.translatable("pv.addon.discs.actionbar.loading")
                    .withStyle(McTextStyle.YELLOW)
            )
        } else {
            player.sendActionBar(Component.text("Loading...", NamedTextColor.YELLOW))
        }

        val track = try {
            audioPlayerManager.getTrack(identifier).await()
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unexpected error"
            if (voicePlayer != null) {
                voicePlayer.instance.sendActionBar(
                    McTextComponent.translatable(
                        "pv.addon.discs.actionbar.track_not_found",
                        errorMsg
                    )
                        .withStyle(McTextStyle.RED)
                )
            } else {
                player.sendActionBar(Component.text("Track not found: $errorMsg", NamedTextColor.RED))
            }

            debugLogger.log("Failed to load track", e)
            return@launch
        }

        if (!canPlay(track)) {
            if (voicePlayer != null) {
                voicePlayer.instance.sendTranslatable(
                    "pv.addon.discs.error.horn_too_long", config.goatHorn.maxDurationSeconds
                )
            } else {
                player.sendMessage(Component.text("Track is too long. Maximum duration in seconds: ${config.goatHorn.maxDurationSeconds}", NamedTextColor.RED))
            }
            return@launch
        }

        if (!suspendSyncFor(player, voiceServer, plugin) {
            PlayTrackFromGoatHornEvent(voicePlayer, track).callEvent()
        }) return@launch

        val trackName = (item.itemMeta
            ?.lore()
            ?.getOrNull(0)
            ?.let { it as? TextComponent }
            ?.content()
            ?: track.info.title).cleanTrackName()

        val distance = config.goatHorn.distance

        val pvSource = if (voicePlayer != null && sourceLine != null) {
            sourceLine?.createPlayerSource(voicePlayer, !config.monoSources)?.apply {
                setName(trackName)
                if (filters.isNotEmpty()) {
                    removeFilter(filters.first()) // the first filter is always the "self" filter
                }
            }
        } else null

        val svcChannel = svcPlugin?.createEntityChannel(player, distance.toFloat())?.apply {
            setFilter { sp ->
                svcPlugin?.hasClient(sp.uuid) == true
            }
        }

        val actionbarMessage = McTextComponent.translatable(
            "pv.addon.discs.actionbar.playing", trackName
        )

        if (config.goatHorn.visualizeDistance && voicePlayer != null) {
            voicePlayer.visualizeDistance(
                voicePlayer.instance.getPosition(),
                distance.toInt(),
                0xf1c40f
            )
        }

        suspendSyncFor(player, voiceServer, plugin) { player.location.getNearbyPlayers(distance.toDouble()) }
            .forEach { nearby ->
                if ((svcPlugin?.hasClient(nearby.uniqueId) != true && VoiceBridgeCompat.isPlayerListeningOnPv(nearby.uniqueId, voiceServer))) {
                    val vp = voiceServer?.let { nearby.asVoicePlayer(it) }
                    vp?.sendAnimatedActionBar(actionbarMessage)
                } else {
                    nearby.sendActionBar(Component.text("Now playing: $trackName", NamedTextColor.GOLD))
                }
            }

        debugLogger.log("Starting track job \"$trackName\" with distance $distance on $player")

        val job = audioPlayerManager.startTrackJob(
            track = track,
            pvSource = pvSource,
            distance = distance,
            svcChannel = svcChannel
        )
        try {
            job.join()
        } catch (e: HornCancellationCause) {
            debugLogger.log("Track \"$trackName\" on $player cancelled: ${e.message}")
            throw e
        } finally {
            withContext(NonCancellable) {
                debugLogger.log("Track \"$trackName\" on $player ended")

                job.cancelAndJoin()
                pvSource?.remove()

                jobByPlayer.remove(player)
            }
        }
    }
}
