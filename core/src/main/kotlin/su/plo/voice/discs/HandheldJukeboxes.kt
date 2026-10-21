package su.plo.voice.discs

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.discs.bridge.VoiceBridgeCompat
import su.plo.voice.discs.svc.SvcDiscsPlugin
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Portable devices have their own persistent queue; they never use horn playback state. */
class HandheldJukeboxes : Listener, PluginKoinComponent {
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val config: AddonConfig by getter()
    private val manager: PlasmoAudioPlayerManager by getter()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val sourceLine: ServerSourceLine? by getterOrNull()
    private val svc: SvcDiscsPlugin? by getterOrNull()
    private val key = NamespacedKey.fromString("vc-addon-discs:handheld_jukebox")!!
    private val gson = Gson()
    private val active = ConcurrentHashMap<UUID, Session>()
    private var monitor: Job? = null

    data class Song(val identifier: String, val title: String)
    data class Device(val id: String = UUID.randomUUID().toString(), val queue: MutableList<Song> = mutableListOf(),
                      var volume: Int = 100, var selected: Int = 0) {
        fun enqueue(song: Song): Boolean {
            if (queue.size >= 45) return false
            queue.add(song)
            return true
        }
        fun advance(): Boolean {
            selected++
            if (selected >= queue.size) { selected = 0; return false }
            return true
        }
    }
    private class Session(val owner: UUID, val device: Device, val controls: PlaybackControls, var job: Job? = null)
    private class Menu(val device: Device) : InventoryHolder {
        val view: Inventory = Bukkit.createInventory(this, 54, "Handheld Jukebox")
        override fun getInventory(): Inventory = view
    }

    fun start() {
        // Copper nuggets do not exist on older supported Minecraft versions.
        val copper = Material.matchMaterial("COPPER_NUGGET") ?: run {
            plugin.logger.info("Handheld jukebox crafting requires copper nuggets; recipe unavailable on this Minecraft version.")
            return
        }
        val bundle = Material.matchMaterial("BUNDLE") ?: return
        val result = ItemStack(bundle)
        write(result, Device())
        // Identity is assigned on first use, so separate crafts never share a device ID.
        result.editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, "new") }
        val recipeKey = NamespacedKey(plugin, "handheld_jukebox")
        Bukkit.removeRecipe(recipeKey)
        Bukkit.addRecipe(ShapedRecipe(recipeKey, result).apply {
            shape("CJC", "CBC", "CCC")
            setIngredient('C', copper); setIngredient('J', Material.JUKEBOX); setIngredient('B', bundle)
        })
        monitor = scope.launch {
            while (isActive) {
                delay(250)
                active.entries.toList().forEach { (id, session) ->
                    val player = Bukkit.getPlayer(session.owner)
                    if (player == null) stop(id)
                    else suspendSyncFor(player, voiceServer, plugin) {
                        if (player.isDead || held(player, id) == null) stop(id)
                    }
                }
            }
        }
    }

    private fun marked(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(key, PersistentDataType.STRING) == true

    private fun read(item: ItemStack): Device? {
        val json = item.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING) ?: return null
        return try {
            if (json == "new") Device() else gson.fromJson(json, Device::class.java).also {
                UUID.fromString(it.id)
                require(it.queue.size <= 45 && it.volume in 0..100)
            }
        } catch (_: Exception) { null }
    }

    private fun write(item: ItemStack, device: Device) {
        item.editMeta { meta ->
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, gson.toJson(device))
            meta.displayName(Component.text("Handheld Jukebox", NamedTextColor.GOLD))
            meta.lore(listOf(Component.text("Right-click to open your music queue.", NamedTextColor.GRAY),
                Component.text("Keep in either hand to play. Click burned discs to add tracks.", NamedTextColor.GRAY)))
        }
    }

    private fun held(player: Player, id: UUID): ItemStack? =
        listOf(player.inventory.itemInMainHand, player.inventory.itemInOffHand).firstOrNull {
            marked(it) && read(it)?.id == id.toString()
        }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun interact(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (!marked(item) || event.action !in setOf(org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) return
        event.isCancelled = true
        if (item.amount != 1) {
            event.player.sendMessage(Component.text("Separate handheld jukeboxes into individual items before use.", NamedTextColor.RED))
            return
        }
        val saved = read(item) ?: return
        val device = active[UUID.fromString(saved.id)]?.device ?: saved
        write(item, device)
        later(event.player) { open(event.player, device) }
    }

    // Prevent placing the bundle's contents or nesting items inside the custom device.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protectBundle(event: InventoryClickEvent) {
        if (marked(event.cursor) || marked(event.currentItem)) {
            if (event.isRightClick) event.isCancelled = true
        }
    }

    private fun icon(material: Material, title: String, vararg lore: String): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(Component.text(title, NamedTextColor.YELLOW))
            meta.lore(lore.map { Component.text(it, NamedTextColor.GRAY) })
        }
    }

    private fun open(player: Player, device: Device) {
        val menu = Menu(device)
        val state = active[UUID.fromString(device.id)]
        device.queue.forEachIndexed { index, song ->
            menu.view.setItem(index, icon(Material.MUSIC_DISC_CAT, "${index + 1}. ${song.title}",
                "Click to play from here", "Shift-click to remove"))
        }
        menu.view.setItem(45, icon(Material.LIME_DYE, "Play / Pause", state?.controls?.displayTitle ?: "Stopped"))
        menu.view.setItem(46, icon(Material.ARROW, "Next track"))
        menu.view.setItem(47, icon(Material.RED_DYE, "Stop"))
        menu.view.setItem(48, icon(Material.REDSTONE, "Volume -10", "Volume: ${device.volume}%"))
        menu.view.setItem(49, icon(Material.PAPER, "Add music", "Click a burned disc in your inventory to copy its track.", "Queue: ${device.queue.size}/45", "Keep this device in either hand while playing."))
        menu.view.setItem(50, icon(Material.GLOWSTONE_DUST, "Volume +10", "Volume: ${device.volume}%"))
        menu.view.setItem(53, icon(Material.BARRIER, "Close"))
        player.openInventory(menu.view)
    }

    @EventHandler
    fun click(event: InventoryClickEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val device = menu.device
        val id = UUID.fromString(device.id)
        val item = held(player, id) ?: return
        val slot = event.rawSlot
        if (slot < 0) return
        if (slot >= 54) {
            val disc = event.currentItem ?: return
            if (!disc.type.name.startsWith("MUSIC_DISC_")) return
            val identifier = disc.identifier() ?: return
            val song = Song(identifier, disc.itemMeta?.lore()?.firstOrNull()?.let {
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)
            } ?: disc.type.name)
            if (!device.enqueue(song)) {
                player.sendMessage(Component.text("This jukebox queue is full (45 tracks).", NamedTextColor.RED)); return
            }
            write(item, device)
        } else when (slot) {
            in 0..44 -> if (slot < device.queue.size) {
                if (event.isShiftClick) {
                    stop(id)
                    device.queue.removeAt(slot)
                    device.selected = device.selected.coerceAtMost((device.queue.size - 1).coerceAtLeast(0))
                    write(item, device)
                } else { device.selected = slot; write(item, device); play(player, device) }
            }
            45 -> active[id]?.controls?.togglePause() ?: play(player, device)
            46 -> {
                stop(id)
                if (device.selected + 1 < device.queue.size) {
                    device.selected++; write(item, device); play(player, device)
                }
            }
            47 -> stop(id)
            48, 50 -> {
                val delta = if (slot == 48) -10 else 10
                device.volume = (device.volume + delta).coerceIn(0, 100)
                active[id]?.controls?.changeVolume(delta)
                write(item, device)
            }
            53 -> { later(player) { player.closeInventory() }; return }
        }
        later(player) { if (held(player, id) != null) open(player, device) }
    }

    @EventHandler fun drag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Menu || marked(event.oldCursor)) event.isCancelled = true
    }

    private fun later(player: Player, task: () -> Unit) {
        scope.launch {
            delay(50)
            suspendSyncFor(player, voiceServer, plugin) { if (player.isOnline) task() }
        }
    }

    private fun stop(id: UUID) { active.remove(id)?.job?.cancel() }

    private fun play(player: Player, device: Device) {
        val id = UUID.fromString(device.id)
        stop(id)
        if (device.queue.isEmpty()) return
        val session = Session(player.uniqueId, device, PlaybackControls(initialVolume = device.volume))
        active[id] = session
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    val song = suspendSyncFor(player, voiceServer, plugin) {
                        if (held(player, id) == null) null else device.queue.getOrNull(device.selected)
                    } ?: break
                    val track = manager.getTrack(song.identifier).await()
                    session.controls.title = track.info.title
                    val destinations = suspendSyncFor(player, voiceServer, plugin) {
                        if (held(player, id) == null) return@suspendSyncFor null
                        val pvPlayer = if (svc?.hasClient(player.uniqueId) != true &&
                            VoiceBridgeCompat.isPlayerListeningOnPv(player.uniqueId, voiceServer)) {
                            voiceServer?.let { player.asVoicePlayer(it) }
                        } else null
                        val pv = pvPlayer?.let { vp -> sourceLine?.createPlayerSource(vp, !config.monoSources)?.apply {
                            setName("Handheld Jukebox")
                            if (filters.isNotEmpty()) removeFilter(filters.first())
                        } }
                        val channel = svc?.createEntityChannel(player, config.goatHorn.distance.toFloat())?.apply {
                            setFilter { sp -> svc?.hasClient(sp.uuid) == true }
                        }
                        pv to channel
                    } ?: break
                    val (pv, channel) = destinations
                    if (pv == null && channel == null) error("Connect a supported voice chat client to play music.")
                    try {
                        val audio = manager.startTrackJob(track, pvSource = pv, distance = config.goatHorn.distance,
                            svcChannel = channel, controls = session.controls)
                        try { audio.join() } finally { withContext(NonCancellable) { audio.cancelAndJoin() } }
                    } finally { pv?.remove() }
                    if (session.controls.failure != null) error(session.controls.failure!!)
                    val more = suspendSyncFor(player, voiceServer, plugin) {
                        val item = held(player, id) ?: return@suspendSyncFor false
                        val hasNext = device.advance()
                        write(item, device)
                        hasNext
                    }
                    if (!more) break
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                plugin.logger.warning("Handheld jukebox playback failed: ${e.message}")
                suspendSyncFor(player, voiceServer, plugin) {
                    player.sendMessage(Component.text("Handheld jukebox: ${e.message ?: "Playback failed"}", NamedTextColor.RED))
                }
            } finally { active.remove(id, session) }
        }
        session.job?.start()
    }

    fun shutdown() {
        monitor?.cancel()
        active.keys.toList().forEach(::stop)
        Bukkit.removeRecipe(NamespacedKey(plugin, "handheld_jukebox"))
    }
}
