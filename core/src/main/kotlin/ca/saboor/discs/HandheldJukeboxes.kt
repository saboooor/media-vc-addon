package ca.saboor.discs

import ca.saboor.discs.bridge.VoiceBridgeCompat
import ca.saboor.discs.svc.SvcDiscsPlugin
import ca.saboor.discs.utils.extend.suspendSyncFor
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import su.plo.voice.discs.AddonConfig
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.PlasmoAudioPlayerManager
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*

/** Portable devices have their own persistent queue; they never use horn playback state. */
class HandheldJukeboxes : Listener, PluginKoinComponent {
    companion object {
        const val MAX_DISCS = 18
        private val DEVICE_ID_REGEX = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        private val LOW_PASS_VALUES = listOf(0.0, 12000.0, 6000.0, 3000.0, 1500.0, 750.0)
        private val HIGH_PASS_VALUES = listOf(0.0, 80.0, 150.0, 300.0, 600.0, 1200.0)
        private val SEEK_OPTIONS = listOf(15 to "15s", 30 to "30s", 60 to "1m", 300 to "5m")
    }
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val config: AddonConfig by getter()
    private val manager: PlasmoAudioPlayerManager by getter()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val sourceLine: ServerSourceLine? by getterOrNull()
    private val svc: SvcDiscsPlugin? by getterOrNull()
    private val key = NamespacedKey.fromString("vc-addon-discs:handheld_jukebox")!!
    private val idKey = NamespacedKey.fromString("vc-addon-discs:handheld_jukebox_id")!!
    private val bundleMaterial = Material.matchMaterial("BUNDLE")
    private val gson = Gson()
    private val active = ConcurrentHashMap<UUID, Session>()
    private var monitor: Job? = null
    private data class DialogSession(val token: UUID, val device: Device, val expires: Long = System.currentTimeMillis() + 120_000)
    private val dialogs = ConcurrentHashMap<UUID, DialogSession>()

    data class Song(val identifier: String, val title: String, val itemData: String? = null, val slot: Int = -1)
    data class Device(val id: String = UUID.randomUUID().toString(), val queue: MutableList<Song> = mutableListOf(),
                      var volume: Int = 100, var selected: Int = 0,
                      var loop: Boolean = false, var shuffle: Boolean = false,
                      var effects: ca.saboor.discs.audio.AudioFilterConfig = ca.saboor.discs.audio.AudioFilterConfig()) {
        @Transient private var played = mutableSetOf<Int>()
        fun resetOrder() { played.clear() }
        fun replaceQueue(next: List<Song>, current: Song? = queue.getOrNull(selected)) {
            val index = if (current == null) -1 else next.indexOfFirst {
                it.identifier == current.identifier && it.itemData == current.itemData
            }
            queue.clear(); queue.addAll(next)
            selected = if (index >= 0) index else selected.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            resetOrder()
        }
        fun enqueue(song: Song): Boolean {
            if (queue.size >= MAX_DISCS) return false
            queue.add(song)
            return true
        }
        fun advance(): Boolean {
            if (queue.isEmpty()) { selected = 0; return false }
            if (shuffle) {
                played.add(selected)
                var remaining = queue.indices.filterNot { it in played }
                if (remaining.isEmpty()) {
                    played.clear()
                    if (!loop) { selected = 0; return false }
                    remaining = queue.indices.filter { queue.size == 1 || it != selected }
                }
                selected = remaining.random()
                return true
            }
            selected++
            if (selected >= queue.size) { selected = 0; return loop }
            return true
        }
    }
    private class Session(val owner: UUID, val device: Device, @Volatile var controls: PlaybackControls, var job: Job? = null) {
        val restart = java.util.concurrent.atomic.AtomicReference<PlaybackControls?>()
        @Volatile var audio: Job? = null
    }
    private class Menu(val device: Device) : InventoryHolder {
        val view: Inventory = Bukkit.createInventory(this, MAX_DISCS, "Handheld Jukebox Queue")
        override fun getInventory(): Inventory = view
    }

    fun start() {
        // Copper nuggets do not exist on older supported Minecraft versions.
        val copper = Material.matchMaterial("COPPER_NUGGET") ?: run {
            plugin.logger.info("Handheld jukebox crafting requires copper nuggets; recipe unavailable on this Minecraft version.")
            return
        }
        val shard = Material.matchMaterial("AMETHYST_SHARD") ?: return
        val result = ItemStack(shard)
        write(result, Device())
        // Identity is assigned on first use, so separate crafts never share a device ID.
        result.editMeta {
            it.persistentDataContainer.set(key, PersistentDataType.STRING, "new")
            it.persistentDataContainer.remove(idKey)
        }
        val recipeKey = NamespacedKey(plugin, "handheld_jukebox")
        Bukkit.removeRecipe(recipeKey)
        Bukkit.addRecipe(ShapedRecipe(recipeKey, result).apply {
            shape("CJC", "CSC", "CCC")
            setIngredient('C', copper); setIngredient('J', Material.JUKEBOX); setIngredient('S', shard)
        })
        monitor = scope.launch {
            while (isActive) {
                delay(250)
                active.entries.toList().forEach { (id, session) ->
                    val player = Bukkit.getPlayer(session.owner)
                    if (player == null) stop(id)
                    else suspendSyncFor(player, voiceServer, plugin) {
                        if (player.isDead || carried(player, id) == null) stop(id)
                    }
                }
            }
        }
    }

    private fun marked(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(key, PersistentDataType.STRING) == true

    private fun deviceId(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        meta.persistentDataContainer.get(idKey, PersistentDataType.STRING)?.let { return it }
        val json = meta.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return null
        if (json == "new") return null
        DEVICE_ID_REGEX.find(json)?.groupValues?.get(1)?.let { return it }
        return read(item)?.id
    }

    private fun read(item: ItemStack): Device? {
        val json = item.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING) ?: return null
        return try {
            if (json == "new") Device() else gson.fromJson(json, Device::class.java).also {
                UUID.fromString(it.id)
                // Keep older larger queues accessible so players can remove entries without data loss.
                require(it.queue.size <= 45 && it.volume in 0..100)
            }
        } catch (_: Exception) { null }
    }

    private fun write(item: ItemStack, device: Device) {
        item.editMeta { meta ->
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, gson.toJson(device))
            meta.persistentDataContainer.set(idKey, PersistentDataType.STRING, device.id)
            meta.displayName(Component.text("Handheld Jukebox", NamedTextColor.GOLD))
            meta.lore(listOf(Component.text("Right-click to open your music queue.", NamedTextColor.GRAY),
                Component.text("Keep in your inventory to play. Open Queue stores real discs.", NamedTextColor.GRAY)))
        }
    }

    private fun carried(player: Player, id: UUID): ItemStack? {
        val idStr = id.toString()
        return player.inventory.contents.filterNotNull().firstOrNull {
            marked(it) && deviceId(it) == idStr
        }
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
        later(event.player) { showControls(event.player, device) }
    }

    // Protect legacy bundle devices crafted before the amethyst-shard item change.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protectLegacyBundle(event: InventoryClickEvent) {
        val bundle = bundleMaterial ?: return
        if ((event.cursor?.type == bundle && marked(event.cursor)) ||
            (event.currentItem?.type == bundle && marked(event.currentItem))) {
            if (event.isRightClick) event.isCancelled = true
        }
    }

    private fun isDisc(item: ItemStack?): Boolean = item != null &&
        item.type.name.startsWith("MUSIC_DISC_") && item.identifier() != null

    private fun encodeDisc(item: ItemStack): String {
        val bytes = java.io.ByteArrayOutputStream()
        org.bukkit.util.io.BukkitObjectOutputStream(bytes).use { it.writeObject(item) }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decodeDisc(song: Song): ItemStack {
        song.itemData?.let { encoded ->
            val bytes = java.util.Base64.getDecoder().decode(encoded)
            return org.bukkit.util.io.BukkitObjectInputStream(java.io.ByteArrayInputStream(bytes)).use {
                it.readObject() as ItemStack
            }
        }
        // Older queues stored only copied track references, without the original disc material.
        return ItemStack(Material.MUSIC_DISC_CAT).apply {
            editMeta {
                it.persistentDataContainer.set(AddonKeys.identifierKey, PersistentDataType.STRING, song.identifier)
                it.lore(listOf(Component.text(song.title, NamedTextColor.GRAY)))
            }
        }
    }

    private fun song(item: ItemStack, slot: Int): Song = Song(item.identifier()!!,
        item.itemMeta?.lore()?.firstOrNull()?.let {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)
        } ?: item.type.name, encodeDisc(item), slot)

    private fun openQueue(player: Player, device: Device) {
        val item = carried(player, UUID.fromString(device.id)) ?: return
        if (player.openInventory.topInventory.holder is Menu) player.closeInventory()
        val menu = Menu(device)
        // Decode everything before changing the device, so unreadable item data is never erased.
        val decoded = try { device.queue.map(::decodeDisc) } catch (e: Exception) {
            plugin.logger.warning("Could not read portable jukebox discs: ${e.message}")
            player.sendMessage(Component.text("Could not read this jukebox's discs. Nothing was removed.", NamedTextColor.RED))
            return
        }
        val overflow = mutableListOf<ItemStack>()
        device.queue.forEachIndexed { index, entry ->
            val slot = if (entry.slot in 0 until MAX_DISCS) entry.slot else index
            if (slot < MAX_DISCS && menu.view.getItem(slot) == null) menu.view.setItem(slot, decoded[index])
            else overflow += decoded[index]
        }
        // Save the migrated, physical inventory before returning legacy overflow.
        val old = device.queue.getOrNull(device.selected)
        val updated = (0 until MAX_DISCS).mapNotNull { slot -> menu.view.getItem(slot)?.let { song(it, slot) } }
        if (updated != device.queue) device.replaceQueue(updated, old)
        write(item, device)
        overflow.forEach { disc -> player.inventory.addItem(disc).values.forEach { player.world.dropItemNaturally(player.location, it) } }
        player.openInventory(menu.view)
    }

    private fun saveQueue(player: Player, menu: Menu) {
        val item = carried(player, UUID.fromString(menu.device.id)) ?: return
        val next = (0 until MAX_DISCS).mapNotNull { slot ->
            menu.view.getItem(slot)?.takeIf(::isDisc)?.let { song(it, slot) }
        }
        val device = menu.device
        if (next == device.queue) return
        val current = device.queue.getOrNull(device.selected)
        val previousId = current?.identifier
        device.replaceQueue(next, current)
        if (active[UUID.fromString(device.id)] != null &&
            (current == null || next.none { it.identifier == previousId && it.itemData == current.itemData })) {
            stop(UUID.fromString(device.id))
        }
        write(item, device)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        val player = event.whoClicked as? Player ?: return
        if (carried(player, UUID.fromString(menu.device.id)) == null) { event.isCancelled = true; return }
        val top = event.rawSlot in 0 until MAX_DISCS
        val incoming = when (event.action) {
            org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP,
            org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD ->
                if (event.hotbarButton >= 0) player.inventory.getItem(event.hotbarButton) else player.inventory.itemInOffHand
            else -> event.cursor
        }
        // Keep the device in place while its contents are open, and never nest devices.
        if (marked(event.currentItem) || marked(event.cursor) || marked(incoming)) {
            event.isCancelled = true; return
        }
        val placesInTop = top && event.action in setOf(
            org.bukkit.event.inventory.InventoryAction.PLACE_ALL,
            org.bukkit.event.inventory.InventoryAction.PLACE_ONE,
            org.bukkit.event.inventory.InventoryAction.PLACE_SOME,
            org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR,
            org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP,
            org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD)
        val shiftIntoTop = !top && event.isShiftClick && event.rawSlot >= MAX_DISCS
        if ((placesInTop && incoming != null && !incoming.type.isAir && !isDisc(incoming)) ||
            (shiftIntoTop && !isDisc(event.currentItem))) {
            event.isCancelled = true; return
        }
        // Vanilla performs the transfer; persist the resulting real slots on the following tick.
        later(player) { if (player.openInventory.topInventory === menu.view) saveQueue(player, menu) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        val player = event.whoClicked as? Player ?: return
        if (carried(player, UUID.fromString(menu.device.id)) == null || marked(event.oldCursor) ||
            (event.rawSlots.any { it < MAX_DISCS } && !isDisc(event.oldCursor))) {
            event.isCancelled = true; return
        }
        later(player) { if (player.openInventory.topInventory === menu.view) saveQueue(player, menu) }
    }

    @EventHandler
    fun closeQueue(event: org.bukkit.event.inventory.InventoryCloseEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        val player = event.player as? Player ?: return
        saveQueue(player, menu)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun dropDevice(event: org.bukkit.event.player.PlayerDropItemEvent) {
        if (event.player.openInventory.topInventory.holder is Menu && marked(event.itemDrop.itemStack))
            event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        val menu = event.player.openInventory.topInventory.holder as? Menu
        if (menu != null) saveQueue(event.player, menu)
        dialogs.remove(event.player.uniqueId)
        active.entries.filter { it.value.owner == event.player.uniqueId }.forEach { stop(it.key) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: org.bukkit.event.entity.PlayerDeathEvent) {
        val menu = event.entity.openInventory.topInventory.holder as? Menu ?: return
        saveQueue(event.entity, menu)
        event.drops.filter { marked(it) && deviceId(it) == menu.device.id }.forEach { write(it, menu.device) }
        event.entity.closeInventory()
    }

    fun handleControl(player: Player, token: String, action: String) {
        val dialog = dialogs[player.uniqueId] ?: return
        if (dialog.token.toString() != token || System.currentTimeMillis() > dialog.expires) return
        val device = dialog.device
        val id = UUID.fromString(device.id)
        val item = carried(player, id) ?: return
        dialogs.remove(player.uniqueId, dialog)
        val controls = active[id]?.controls
        var page = "refresh"
        when {
            action == "queue_menu" -> { later(player) { openQueue(player, device) }; return }
            action in setOf("volume_menu", "effects_menu", "fx_eq_menu", "seek") -> page = action
            action == "play" -> play(player, device)
            action == "restart" -> active[id]?.let { session ->
                session.restart.set(session.controls.copyForRestart())
                session.audio?.cancel()
            }
            action == "pause" -> controls?.togglePause()
            action == "stop" -> stop(id)
            action == "skip" -> { stop(id); if (device.advance()) play(player, device) }
            action == "loop" -> device.loop = !device.loop
            action == "shuffle" -> device.shuffle = !device.shuffle
            action.startsWith("volume_") -> {
                val amount = action.substringAfterLast('_').toIntOrNull() ?: return
                val old = device.volume
                device.volume = when {
                    action.startsWith("volume_minus_") -> old - amount
                    action.startsWith("volume_plus_") -> old + amount
                    action.startsWith("volume_set_") -> amount
                    else -> return
                }.coerceIn(0, 100)
                controls?.changeVolume(device.volume - old)
                page = "volume_menu"
            }
            action.startsWith("seek_") -> {
                val seconds = action.substringAfterLast('_').toLongOrNull()?.coerceIn(0, 3600) ?: return
                val delta = if (action.startsWith("seek_minus_")) -seconds else seconds
                controls?.seekTo(controls.position + delta * 1000)
                page = "seek"
            }
            action.startsWith("fx_") -> {
                device.effects = changeEffects(device.effects, action)
                controls?.applyFilterConfig(device.effects)
                page = if (action.startsWith("fx_set_eq_")) "fx_eq_menu" else "effects_menu"
            }
        }
        write(item, device)
        later(player) { showControls(player, device, page) }
    }

    private fun changeEffects(cur: ca.saboor.discs.audio.AudioFilterConfig, action: String): ca.saboor.discs.audio.AudioFilterConfig {
        if (action.startsWith("fx_set_eq_")) {
            val preset = ca.saboor.discs.audio.EqPreset.fromId(action.removePrefix("fx_set_eq_")) ?: return cur
            return cur.copy(eqPreset = preset.id, bands = null)
        }
        return when (action) {
            "fx_clear" -> ca.saboor.discs.audio.AudioFilterConfig()
            "fx_toggle_8d" -> cur.copy(rotation = !cur.rotation)
            "fx_toggle_echo" -> cur.copy(echo = !cur.echo)
            "fx_toggle_underwater" -> cur.copy(underwater = !cur.underwater)
            "fx_toggle_tremolo" -> cur.copy(tremolo = !cur.tremolo)
            "fx_toggle_vibrato" -> cur.copy(vibrato = !cur.vibrato)
            "fx_toggle_nightcore" -> cur.copy(speed = if (cur.speed == 1.25) 1.0 else 1.25)
            "fx_toggle_vaporwave" -> cur.copy(speed = if (cur.speed == 0.8) 1.0 else 0.8)
            "fx_tempo_down" -> cur.copy(tempo = (cur.tempo - 0.05).coerceIn(0.5, 2.0))
            "fx_tempo_up" -> cur.copy(tempo = (cur.tempo + 0.05).coerceIn(0.5, 2.0))
            "fx_pitch_down" -> cur.copy(pitchSemitones = (cur.pitchSemitones - 1.0).coerceIn(-12.0, 12.0))
            "fx_pitch_up" -> cur.copy(pitchSemitones = (cur.pitchSemitones + 1.0).coerceIn(-12.0, 12.0))
            "fx_timescale_reset" -> cur.copy(tempo = 1.0, pitchSemitones = 0.0)
            "fx_lowpass", "fx_highpass" -> {
                val low = action == "fx_lowpass"
                val values = if (low) LOW_PASS_VALUES else HIGH_PASS_VALUES
                val current = if (low) cur.lowPassHz else cur.highPassHz
                val idx = values.indexOf(current)
                val next = values[if (idx >= 0) (idx + 1) % values.size else 0]
                if (low) cur.copy(lowPassHz = next) else cur.copy(highPassHz = next)
            }
            else -> cur
        }
    }

    private fun showControls(player: Player, device: Device, menu: String = "refresh") {
        if (carried(player, UUID.fromString(device.id)) == null) return
        val controls = active[UUID.fromString(device.id)]?.controls
        val dialog = DialogSession(UUID.randomUUID(), device)
        dialogs[player.uniqueId] = dialog
        val prefix = "/disc portable ${dialog.token} "
        val page = when (menu) {
            "volume_menu" -> PlaybackMenus.volume(prefix, device.volume, controls != null)
            "effects_menu" -> PlaybackMenus.effects(prefix, device.effects)
            "fx_eq_menu" -> PlaybackMenus.equalizer(prefix, device.effects)
            else -> {
                val body = mutableListOf<Component>()
                val buttons = mutableListOf<Pair<String, String>>()
                if (controls != null) {
                    body += Component.text(controls.displayTitle, NamedTextColor.GOLD)
                    body += PlaybackDialogView.progress(controls.position, controls.duration, controls.isStream)
                    body += Component.text("${if (controls.paused) "Paused" else "Playing"} · Volume ${controls.volume}%", NamedTextColor.GRAY)
                } else body += Component.text("Jukebox is idle.")
                if (menu == "seek" && controls?.seekable == true) {
                    for ((seconds, label) in SEEK_OPTIONS) {
                        buttons += "−$label" to (prefix + "seek_minus_$seconds")
                        buttons += "+$label" to (prefix + "seek_plus_$seconds")
                    }
                    buttons += "Refresh progress" to (prefix + "seek")
                    buttons += "Back to playback" to (prefix + "refresh")
                } else {
                    if (controls != null) {
                        buttons += (if (controls.paused) "Resume" else "Pause") to (prefix + "pause")
                        buttons += "Skip" to (prefix + "skip")
                    } else buttons += "Play queue" to (prefix + "play")
                    buttons += "Open Queue" to (prefix + "queue_menu")
                    buttons += "Volume" to (prefix + "volume_menu")
                    buttons += "Audio effects" to (prefix + "effects_menu")
                    buttons += "Loop: ${if (device.loop) "On" else "Off"}" to (prefix + "loop")
                    buttons += "Shuffle: ${if (device.shuffle) "On" else "Off"}" to (prefix + "shuffle")
                    buttons += "Refresh" to (prefix + "refresh")
                    if (controls != null) {
                        buttons += "Restart playback" to (prefix + "restart")
                        buttons += "Stop playback" to (prefix + "stop")
                        if (controls.seekable) buttons += "Seek" to (prefix + "seek")
                    }
                }
                PlaybackMenus.Page(if (menu == "seek") "Seek" else "Jukebox controls", body, buttons)
            }
        }
        try {
            ca.saboor.discs.dialog.PaperPlaybackDialog.show(player,
                listOf(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), page.body)),
                page.buttons, title = page.title, columns = 2)
        } catch (_: ClassNotFoundException) {
            page.body.forEach { player.sendMessage(it) }
            page.buttons.forEach { (label, command) -> player.sendMessage(Component.text("[$label]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command))) }
        } catch (e: Exception) { plugin.logger.warning("Could not open handheld jukebox controls: ${e.message}") }
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
        device.resetOrder()
        val session = Session(player.uniqueId, device, PlaybackControls(initialVolume = device.volume, initialFilterConfig = device.effects))
        active[id] = session
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    session.restart.getAndSet(null)?.let { session.controls = it }
                    val song = suspendSyncFor(player, voiceServer, plugin) {
                        if (carried(player, id) == null) null else device.queue.getOrNull(device.selected)
                    } ?: break
                    val track = manager.getTrack(song.identifier).await()
                    if (session.restart.get() != null) continue
                    session.controls.title = track.info.title
                    val destinations = suspendSyncFor(player, voiceServer, plugin) {
                        if (carried(player, id) == null) return@suspendSyncFor null
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
                        session.audio = audio
                        if (session.restart.get() != null) audio.cancel()
                        try { audio.join() } finally {
                            withContext(NonCancellable) { audio.cancelAndJoin() }
                            session.audio = null
                        }
                    } finally { pv?.remove() }
                    if (session.restart.get() != null) continue
                    if (session.controls.failure != null) error(session.controls.failure!!)
                    val more = suspendSyncFor(player, voiceServer, plugin) {
                        val item = carried(player, id) ?: return@suspendSyncFor false
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
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.openInventory.topInventory.holder is Menu) player.closeInventory()
        }
        monitor?.cancel()
        dialogs.clear()
        active.keys.toList().forEach(::stop)
        Bukkit.removeRecipe(NamespacedKey(plugin, "handheld_jukebox"))
    }
}

