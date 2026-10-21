package su.plo.voice.discs.screen

import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*

/** A fixed plane just in front of the map wall, never a player-facing hologram. */
class ScreenSubtitles(private val screen: Screen, private val player: VideoPlayer) : PluginKoinComponent {
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    @Volatile var cues = SubtitleCues.EMPTY
    @Volatile private var closed = false
    private var display: Entity? = null
    private var job: Job? = null

    fun start() {
        val type = runCatching { EntityType.valueOf("TEXT_DISPLAY") }.getOrNull() ?: return
        job = scope.launch {
            while (isActive && !closed) {
                val location = screen.getCenterLocation() ?: break
                location.add(screen.facing.modX * 0.09, -screen.rows / 2.0 + 0.15, screen.facing.modZ * 0.09)
                location.yaw = when (screen.facing.name) { "NORTH" -> 180f; "EAST" -> 270f; "WEST" -> 90f; else -> 0f }
                val text = if (screen.subtitlesEnabled) cues.textAt(player.displayedPositionMs) else ""
                suspendSyncAt(location, voiceServer, plugin) {
                    if (closed) return@suspendSyncAt
                    if (!location.world!!.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return@suspendSyncAt
                    if (text.isEmpty()) { display?.remove(); display = null; return@suspendSyncAt }
                    val entity = display?.takeIf { it.isValid } ?: location.world!!.spawnEntity(location, type).also {
                        display = it
                        it.isPersistent = false
                        val cls = Class.forName("org.bukkit.entity.Display")
                        val billboard = Class.forName("org.bukkit.entity.Display\$Billboard")
                        cls.getMethod("setBillboard", billboard).invoke(it, billboard.enumConstants.first { e -> (e as Enum<*>).name == "FIXED" })
                        val textClass = Class.forName("org.bukkit.entity.TextDisplay")
                        textClass.getMethod("setLineWidth", Int::class.javaPrimitiveType).invoke(it, (screen.columns * 32).coerceAtLeast(32))
                        textClass.getMethod("setShadowed", Boolean::class.javaPrimitiveType).invoke(it, true)
                        textClass.getMethod("setSeeThrough", Boolean::class.javaPrimitiveType).invoke(it, false)
                    }
                    Class.forName("org.bukkit.entity.TextDisplay").getMethod("text", Component::class.java).invoke(entity, Component.text(text))
                }
                delay(50)
            }
        }
    }
    fun onUnload(event: ChunkUnloadEvent) {
        val entity = display ?: return
        val loc = entity.location
        if (loc.world == event.world && (loc.blockX shr 4) == event.chunk.x && (loc.blockZ shr 4) == event.chunk.z) {
            entity.remove(); display = null
        }
    }
    fun stop(immediate: Boolean = false) {
        closed = true
        job?.cancel()
        val entity = display ?: return
        display = null
        if (immediate) entity.remove()
        else scope.launch { suspendSyncAt(entity.location, voiceServer, plugin) { entity.remove() } }
    }
}
