package su.plo.voice.discs.event

import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.PlaybackDialogView
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import java.util.concurrent.ConcurrentHashMap

/** One transient, automatically updated display per jukebox, including during crossfades. */
class JukeboxProgressDisplay(private val jukeboxes: JukeboxEventListener) : Listener, PluginKoinComponent {
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val displays = ConcurrentHashMap<Block, Entity>()
    private val displayType = runCatching { EntityType.valueOf("TEXT_DISPLAY") }.getOrNull()
    private var updater: Job? = null

    fun start() {
        if (displayType == null) return
        updater = scope.launch {
            while (isActive) {
                val blocks = displays.keys.toSet() + jukeboxes.controlsByBlock.keys.toSet()
                for (block in blocks) {
                    suspendSyncAt(block.location, voiceServer, plugin) {
                        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) {
                            displays.remove(block)
                            return@suspendSyncAt
                        }
                        val controls = jukeboxes.controlsByBlock[block]
                        if (controls == null || block.type != Material.JUKEBOX) {
                            displays.remove(block)?.remove()
                            return@suspendSyncAt
                        }
                        val display = displays[block]?.takeIf { it.isValid } ?: create(block).also { displays[block] = it }
                        val text = Component.text(controls.title.take(80), NamedTextColor.GOLD)
                            .append(Component.text(if (controls.paused) "\nPaused\n" else "\n", NamedTextColor.GRAY))
                            .append(PlaybackDialogView.progress(controls.position, controls.duration, controls.isStream))
                        display.javaClass.getMethod("text", Component::class.java).invoke(display, text)
                    }
                }
                delay(250)
            }
        }
    }

    private fun create(block: Block): Entity {
        val height = if (block.getRelative(0, 1, 0).state is Chest) 2.35 else 1.35
        val display = block.world.spawnEntity(block.location.add(0.5, height, 0.5), displayType!!)
        try {
            display.isPersistent = false
            val billboardClass = Class.forName("org.bukkit.entity.Display\$Billboard")
            val center = billboardClass.enumConstants.first { (it as Enum<*>).name == "CENTER" }
            display.javaClass.getMethod("setBillboard", billboardClass).invoke(display, center)
            display.javaClass.getMethod("setShadowed", Boolean::class.javaPrimitiveType).invoke(display, true)
            display.javaClass.getMethod("setLineWidth", Int::class.javaPrimitiveType).invoke(display, 260)
            return display
        } catch (e: Exception) {
            display.remove()
            throw e
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        displays.keys.filter { it.inChunk(event.chunk) }.forEach { displays.remove(it)?.remove() }
    }

    fun shutdown() {
        updater?.cancel()
        displays.values.forEach { it.remove() }
        displays.clear()
    }
}
