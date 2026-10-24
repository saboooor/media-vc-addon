package ca.saboor.discs.command.subcommand

import ca.saboor.discs.utils.extend.suspendSyncAt
import ca.saboor.discs.utils.extend.suspendSyncFor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.block.Jukebox
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.event.JukeboxEventListener

class NearbyCommand(private val jukeboxes: JukeboxEventListener) : SubCommand() {
    override val name = "nearby"
    override val permissions = listOf("nearby" to PermissionDefault.OP)
    private val searching = ConcurrentHashMap.newKeySet<UUID>()
    private data class Hit(val x: Int, val y: Int, val z: Int, val title: String, val state: String, val distanceSquared: Double)

    override fun checkCanExecute(sender: CommandSender) =
        sender.hasPermission("pv.addon.discs.nearby") || sender.hasPermission("vc.addon.discs.nearby")

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> =
        if (checkCanExecute(source) && arguments.size == 2)
            listOf("32", "64", "128", "256").filter { it.startsWith(arguments[1]) } else emptyList()

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        if (!checkCanExecute(sender)) { sender.sendMessage(Component.text("No permission to locate jukeboxes.", NamedTextColor.RED)); return }
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Run this command in-game near the sound.", NamedTextColor.RED)); return
        }
        val radius = if (arguments.size >= 2) arguments[1].toIntOrNull() else 64
        val page = if (arguments.size >= 3) arguments[2].toIntOrNull() else 1
        if (arguments.size > 3 || radius == null || radius !in 1..256 || page == null || page < 1) {
            player.sendMessage(Component.text("Usage: /disc nearby [radius 1-256] [page] (default: 64 blocks)", NamedTextColor.RED)); return
        }
        if (!searching.add(player.uniqueId)) {
            player.sendMessage(Component.text("A nearby jukebox search is already running.", NamedTextColor.YELLOW)); return
        }
        val origin = player.location.clone()
        val world = player.world
        val query = NearbyJukeboxQuery(origin.x, origin.y, origin.z, radius)
        // Snapshot loaded chunk handles only. Each tile read runs on that chunk's owning thread.
        val chunks = world.loadedChunks.filter { it.x in query.minChunkX..query.maxChunkX && it.z in query.minChunkZ..query.maxChunkZ }
        player.sendMessage(Component.text("Searching loaded chunks within $radius blocks...", NamedTextColor.GRAY))
        scope.launch {
            try {
                val hits = mutableListOf<Hit>()
                // Bound scheduling work per batch so large radii do not flood the server scheduler.
                for (batch in chunks.chunked(16)) {
                    ensureActive()
                    hits += batch.map { chunk -> async {
                        suspendSyncAt(Location(world, chunk.x * 16.0 + 8, origin.y, chunk.z * 16.0 + 8), voiceServer, plugin) {
                            if (!world.isChunkLoaded(chunk.x, chunk.z)) return@suspendSyncAt emptyList<Hit>()
                            chunk.tileEntities.filterIsInstance<Jukebox>().mapNotNull { jukebox ->
                                if (!query.contains(jukebox.x, jukebox.y, jukebox.z)) return@mapNotNull null
                                val block = jukebox.block
                                val controls = jukeboxes.controlsByBlock[block]?.takeIf { jukeboxes.isPlaying(block) }
                                if (controls == null && !jukebox.isPlaying) return@mapNotNull null
                                val title = controls?.title ?: jukebox.record.type.name.lowercase().replace('_', ' ')
                                val state = if (controls?.paused == true) "Paused" else if (controls != null) "Custom" else "Vanilla"
                                Hit(jukebox.x, jukebox.y, jukebox.z, title.take(120), state,
                                    query.distanceSquared(jukebox.x, jukebox.y, jukebox.z))
                            }
                        }
                    } }.awaitAll().flatten()
                }
                val ordered = hits.sortedWith(compareBy<Hit> { it.distanceSquared }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
                suspendSyncFor(player, voiceServer, plugin) {
                    if (!player.isOnline || !checkCanExecute(player)) return@suspendSyncFor
                    if (ordered.isEmpty()) {
                        player.sendMessage(Component.text("No playing jukeboxes found within $radius blocks in ${world.name} (loaded chunks only).", NamedTextColor.YELLOW))
                        return@suspendSyncFor
                    }
                    val pages = (ordered.size + 9) / 10
                    if (page > pages) {
                        player.sendMessage(Component.text("Only $pages page(s) of nearby jukeboxes. Use /disc nearby $radius $pages.", NamedTextColor.YELLOW))
                        return@suspendSyncFor
                    }
                    player.sendMessage(Component.text("${ordered.size} nearby jukebox(es) · ${world.name} · $radius blocks · Page $page/$pages", NamedTextColor.GOLD))
                    for (hit in ordered.drop((page - 1) * 10).take(10)) {
                        val coordinates = "${hit.x} ${hit.y} ${hit.z}"
                        player.sendMessage(Component.text("• $coordinates", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.copyToClipboard(coordinates))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to copy coordinates")))
                            .append(Component.text(" · ${ceil(sqrt(hit.distanceSquared)).toInt()}m · ${hit.state} · ${hit.title}", NamedTextColor.GRAY)))
                    }
                    if (page < pages) player.sendMessage(Component.text("Next page →", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/disc nearby $radius ${page + 1}")))
                    if (page > 1) player.sendMessage(Component.text("← Previous page", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/disc nearby $radius ${page - 1}")))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                plugin.slF4JLogger.warn("Nearby jukebox search failed", e)
                suspendSyncFor(player, voiceServer, plugin) {
                    if (player.isOnline) player.sendMessage(Component.text("Could not finish the jukebox search. Try a smaller radius.", NamedTextColor.RED))
                }
            } finally { searching.remove(player.uniqueId) }
        }
    }
}
