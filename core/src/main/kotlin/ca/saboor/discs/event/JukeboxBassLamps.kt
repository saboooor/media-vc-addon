package ca.saboor.discs.event

import ca.saboor.discs.PlaybackControls
import ca.saboor.discs.utils.PlayerAudience
import ca.saboor.discs.utils.extend.suspendSyncFor
import ca.saboor.discs.utils.extend.suspendSyncAt
import ca.saboor.discs.visualizer.BassLampEnvelope
import ca.saboor.discs.visualizer.BeaconShutter
import ca.saboor.discs.visualizer.LightBand
import ca.saboor.discs.visualizer.TheatreLightFade
import ca.saboor.discs.visualizer.LightBlock
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.inject
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.event.JukeboxEventListener
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*

/** Links player-placed lights and restores temporary off-state replacements on release. */
class JukeboxBassLamps(private val jukeboxes: JukeboxEventListener) : Listener, PluginKoinComponent {
    data class Position(val world: String, val x: Int, val y: Int, val z: Int) {
        fun loadedBlock(): Block? {
            val level = runCatching { Bukkit.getWorld(UUID.fromString(world)) }.getOrNull() ?: return null
            return if (level.isChunkLoaded(x shr 4, z shr 4)) level.getBlockAt(x, y, z) else null
        }
        companion object { fun of(block: Block) = Position(block.world.uid.toString(), block.x, block.y, block.z) }
    }
    data class Link(val lamp: Position, val jukebox: Position, val band: LightBand? = LightBand.BASS, val originalData: String? = null, val shutterData: String? = null, val theatre: Boolean = false) {
        val blockTypeName: String
            get() {
                val raw = originalData?.substringBefore('[')?.substringAfter(':')
                val mat = raw?.let { name -> Material.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?: LightBlock.original(originalData)?.material
                    ?: lamp.loadedBlock()?.type
                    ?: Material.REDSTONE_LAMP
                return mat.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
            }
    }
    private data class Pending(val jukebox: Block, val theatre: Boolean = false, val unlink: Boolean = false, val band: LightBand = LightBand.BASS, val savedHotbar: Array<ItemStack?>? = null, val expires: Long = System.currentTimeMillis() + 180_000)
    private val toolKey by lazy { NamespacedKey(plugin, "light_dye_tool") }

    companion object {
        fun bandDyeMaterial(band: LightBand): Material = when (band) {
            LightBand.DEEP_BASS -> Material.RED_DYE
            LightBand.BASS -> Material.ORANGE_DYE
            LightBand.LOW_MIDS -> Material.YELLOW_DYE
            LightBand.MID -> Material.LIME_DYE
            LightBand.VOCALS -> Material.CYAN_DYE
            LightBand.HIGHS -> Material.LIGHT_BLUE_DYE
            LightBand.SPARKLE -> Material.MAGENTA_DYE
            LightBand.FULL -> Material.WHITE_DYE
        }

        fun bandFromDyeMaterial(material: Material): LightBand? =
            LightBand.entries.firstOrNull { bandDyeMaterial(it) == material }
    }
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val links = ConcurrentHashMap<Position, Link>()
    private val playerEffects by lazy { PlayerLightEffects(plugin, ::refreshPlayerLights) }
    fun lightEffectsEnabled(player: Player) = playerEffects.enabled(player)
    private fun refreshPlayerLights(player: Player) {
        val worldId = player.world.uid.toString()
        val location = player.location
        val range = (plugin.server.viewDistance + 1) * 16.0
        scope.launch {
            for (link in links.values.filter { it.lamp.world == worldId && kotlin.math.abs(it.lamp.x - location.x) <= range && kotlin.math.abs(it.lamp.z - location.z) <= range }) {
                val world = Bukkit.getWorld(UUID.fromString(worldId)) ?: break
                val positions = listOfNotNull(link.lamp, link.shutterData?.let { link.lamp.copy(y = link.lamp.y + 1) })
                for (pos in positions) {
                    val location = Location(world, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                    val data = suspendSyncAt(location, voiceServer, plugin) { pos.loadedBlock()?.blockData } ?: continue
                    suspendSyncFor(player, voiceServer, plugin) {
                        if (player.isOnline && player.world.uid.toString() == worldId) player.sendBlockChange(location, data)
                    }
                }
            }
        }
    }

    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val falloffs = ConcurrentHashMap<Position, Double>()
    private val waveModes = ConcurrentHashMap.newKeySet<Position>()
    private val chaseModes = ConcurrentHashMap.newKeySet<Position>()
    fun isAutoFalloff(jukebox: Block): Boolean = !falloffs.containsKey(Position.of(jukebox))
    fun setAutoFalloff(jukebox: Block, auto: Boolean = true) {
        val pos = Position.of(jukebox)
        if (auto) {
            falloffs.remove(pos)
        } else if (!falloffs.containsKey(pos)) {
            falloffs[pos] = jukeboxes.controlsByBlock[jukebox]?.tempoFalloff() ?: 0.2
        }
    }
    fun isWaveMode(jukebox: Block): Boolean = waveModes.contains(Position.of(jukebox))
    fun setWaveMode(jukebox: Block, enabled: Boolean = true) {
        val pos = Position.of(jukebox)
        if (enabled) waveModes.add(pos) else waveModes.remove(pos)
    }
    fun toggleWaveMode(jukebox: Block): Boolean {
        val next = !isWaveMode(jukebox)
        setWaveMode(jukebox, next)
        return next
    }
    fun isChaseMode(jukebox: Block): Boolean = chaseModes.contains(Position.of(jukebox))
    fun setChaseMode(jukebox: Block, enabled: Boolean = true) {
        val pos = Position.of(jukebox)
        if (enabled) chaseModes.add(pos) else chaseModes.remove(pos)
    }
    fun toggleChaseMode(jukebox: Block): Boolean {
        val next = !isChaseMode(jukebox)
        setChaseMode(jukebox, next)
        return next
    }
    private fun effectiveFalloff(jukebox: Position, controls: PlaybackControls?): Double {
        val manual = falloffs[jukebox]
        if (manual != null) return manual
        return controls?.tempoFalloff() ?: 0.2
    }
    fun falloff(jukebox: Block): Double =
        effectiveFalloff(Position.of(jukebox), jukeboxes.controlsByBlock[jukebox])
    fun setFalloff(jukebox: Block, value: Double) {
        require(value.isFinite() && value in 0.0..1.0)
        falloffs[Position.of(jukebox)] = value
    }
    fun currentBpm(jukebox: Block): Double? = jukeboxes.controlsByBlock[jukebox]?.currentBpm
    private data class LightHighlight(val blockDisplay: Entity?, val textDisplay: Entity?)
    private val highlights = ConcurrentHashMap<Position, MutableList<LightHighlight>>()
    private val highlightJobs = ConcurrentHashMap<Position, Job>()
    private val blockDisplayType = runCatching { EntityType.valueOf("BLOCK_DISPLAY") }.getOrNull()
    private val textDisplayType = runCatching { EntityType.valueOf("TEXT_DISPLAY") }.getOrNull()

    fun isHighlighting(jukebox: Block): Boolean = highlights.containsKey(Position.of(jukebox))

    private fun safeRemove(entity: Entity?) {
        if (entity == null) return
        if (Bukkit.isPrimaryThread()) {
            entity.remove()
        } else {
            scope.launch {
                suspendSyncAt(entity.location, voiceServer, plugin) {
                    entity.remove()
                }
            }
        }
    }

    fun hideHighlights(jukebox: Block) {
        val pos = Position.of(jukebox)
        highlightJobs.remove(pos)?.cancel()
        val list = highlights.remove(pos) ?: return
        list.forEach {
            safeRemove(it.blockDisplay)
            safeRemove(it.textDisplay)
        }
    }

    fun showHighlights(jukebox: Block, durationMs: Long = 30_000L): Int {
        hideHighlights(jukebox)
        val pos = Position.of(jukebox)
        val linksForJuke = linkedIndex().sources[pos].orEmpty()
        if (linksForJuke.isEmpty()) return 0
        val created = mutableListOf<LightHighlight>()
        for (link in linksForJuke) {
            val block = link.lamp.loadedBlock() ?: continue
            val blockDisplay = spawnBlockGlow(block, link)
            val textDisplay = spawnTextHologram(block, link)
            if (blockDisplay != null || textDisplay != null) {
                created.add(LightHighlight(blockDisplay, textDisplay))
            }
        }
        if (created.isNotEmpty()) {
            highlights[pos] = created
            highlightJobs[pos] = scope.launch {
                delay(durationMs)
                hideHighlights(jukebox)
            }
        }
        return created.size
    }

    fun toggleHighlights(jukebox: Block, durationMs: Long = 30_000L): Boolean {
        if (isHighlighting(jukebox)) {
            hideHighlights(jukebox)
            return false
        }
        return showHighlights(jukebox, durationMs) > 0
    }

    private fun bandTextColor(band: LightBand): NamedTextColor = when (band) {
        LightBand.DEEP_BASS -> NamedTextColor.RED
        LightBand.BASS -> NamedTextColor.GOLD
        LightBand.LOW_MIDS -> NamedTextColor.YELLOW
        LightBand.MID -> NamedTextColor.GREEN
        LightBand.VOCALS -> NamedTextColor.AQUA
        LightBand.HIGHS -> NamedTextColor.BLUE
        LightBand.SPARKLE -> NamedTextColor.LIGHT_PURPLE
        LightBand.FULL -> NamedTextColor.WHITE
    }

    private fun bandGlowColor(band: LightBand): org.bukkit.Color = when (band) {
        LightBand.DEEP_BASS -> org.bukkit.Color.fromRGB(220, 20, 60)
        LightBand.BASS -> org.bukkit.Color.fromRGB(255, 140, 0)
        LightBand.LOW_MIDS -> org.bukkit.Color.fromRGB(255, 215, 0)
        LightBand.MID -> org.bukkit.Color.fromRGB(50, 205, 50)
        LightBand.VOCALS -> org.bukkit.Color.fromRGB(0, 220, 220)
        LightBand.HIGHS -> org.bukkit.Color.fromRGB(30, 144, 255)
        LightBand.SPARKLE -> org.bukkit.Color.fromRGB(255, 105, 180)
        LightBand.FULL -> org.bukkit.Color.fromRGB(255, 255, 255)
    }

    private fun spawnBlockGlow(block: Block, link: Link): Entity? {
        val type = blockDisplayType ?: return null
        val loc = Location(block.world, block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
        val entity = runCatching { block.world.spawnEntity(loc, type) }.getOrNull() ?: return null
        try {
            entity.isPersistent = false
            entity.isGlowing = true
            val blockData = LightBlock.original(link.originalData) ?: block.blockData
            entity.javaClass.getMethod("setBlock", org.bukkit.block.data.BlockData::class.java).invoke(entity, blockData)
            val glowColor = if (link.theatre) org.bukkit.Color.fromRGB(255, 215, 0) else bandGlowColor(link.band ?: LightBand.BASS)
            runCatching {
                entity.javaClass.getMethod("setGlowColorOverride", org.bukkit.Color::class.java).invoke(entity, glowColor)
            }
            applyBlockDisplayScale(entity, 1.004f, -0.002f)
            return entity
        } catch (_: Throwable) {
            entity.remove()
            return null
        }
    }

    private fun spawnTextHologram(block: Block, link: Link): Entity? {
        val type = textDisplayType ?: return null
        val yOffset = if (block.getRelative(org.bukkit.block.BlockFace.UP).type.isAir) 1.25
            else if (block.getRelative(org.bukkit.block.BlockFace.DOWN).type.isAir) -0.35
            else 0.5
        val loc = Location(block.world, block.x + 0.5, block.y + yOffset, block.z + 0.5)
        val entity = runCatching { block.world.spawnEntity(loc, type) }.getOrNull() ?: return null
        try {
            entity.isPersistent = false
            val band = link.band ?: LightBand.BASS
            val textComponent = Component.text("✦ ", NamedTextColor.GOLD)
                .append(Component.text(if (link.theatre) "Theatre" else band.label, bandTextColor(band)))
                .append(Component.text(" ✦", NamedTextColor.GOLD))
                .append(Component.newline())
                .append(Component.text(link.blockTypeName, NamedTextColor.GRAY))

            Class.forName("org.bukkit.entity.TextDisplay").getMethod("text", Component::class.java)
                .invoke(entity, textComponent)

            val billboardClass = Class.forName("org.bukkit.entity.Display\$Billboard")
            val center = billboardClass.enumConstants.first { (it as Enum<*>).name == "CENTER" }
            entity.javaClass.getMethod("setBillboard", billboardClass).invoke(entity, center)
            entity.javaClass.getMethod("setShadowed", Boolean::class.javaPrimitiveType).invoke(entity, true)

            runCatching {
                entity.javaClass.getMethod("setSeeThrough", Boolean::class.javaPrimitiveType).invoke(entity, true)
            }
            runCatching {
                entity.javaClass.getMethod("setDefaultBackground", Boolean::class.javaPrimitiveType).invoke(entity, false)
            }
            return entity
        } catch (_: Throwable) {
            entity.remove()
            return null
        }
    }

    private fun applyBlockDisplayScale(display: Entity, scale: Float, offset: Float) {
        runCatching {
            val transfClass = Class.forName("org.bukkit.util.Transformation")
            val vec3fClass = Class.forName("org.joml.Vector3f")
            val quatClass = Class.forName("org.joml.Quaternionf")
            val vec3fCtor = vec3fClass.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val translation = vec3fCtor.newInstance(offset, offset, offset)
            val scaleVec = vec3fCtor.newInstance(scale, scale, scale)
            val quat = quatClass.getConstructor().newInstance()
            val transfCtor = transfClass.getConstructor(vec3fClass, quatClass, vec3fClass, quatClass)
            val transf = transfCtor.newInstance(translation, quat, scaleVec, quat)
            display.javaClass.getMethod("setTransformation", transfClass).invoke(display, transf)
        }
    }

    private data class SourceBandKey(val jukebox: Position, val band: LightBand)
    private data class BandTickState(val lit: Boolean = false, val beatCount: Int = 0, val isBuzzing: Boolean = false, val colorStep: Int = 0)
    private val envelopes = ConcurrentHashMap<SourceBandKey, BassLampEnvelope>()
    private val bandHistories = ConcurrentHashMap<SourceBandKey, ArrayDeque<BandTickState>>()
    private val theatreFades = ConcurrentHashMap<Position, TheatreLightFade>()
    private val litLamps = ConcurrentHashMap.newKeySet<Position>()
    private val consumers = ConcurrentHashMap.newKeySet<PlaybackControls>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(plugin.dataFolder, "bass_lamp_links.json")
    private var updater: Job? = null
    private data class ChunkKey(val world: String, val x: Int, val z: Int)
    private data class Index(val version: Long, val sources: Map<Position, List<Link>>, val chunks: Map<ChunkKey, List<Link>>)
    private val linksVersion = AtomicLong()
    @Volatile private var index = Index(-1, emptyMap(), emptyMap())
    private fun linkedIndex(): Index {
        val version = linksVersion.get()
        if (index.version == version) return index
        val snapshot = links.values.toList()
        return Index(version, snapshot.groupBy { it.jukebox }, snapshot.groupBy {
            ChunkKey(it.lamp.world, it.lamp.x shr 4, it.lamp.z shr 4)
        }).also { index = it }
    }

    private fun createToolItem(action: String, material: Material, name: Component, loreLines: List<Component>): ItemStack {
        val stack = ItemStack(material)
        val meta = stack.itemMeta ?: return stack
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        meta.lore(loreLines.map { it.decoration(TextDecoration.ITALIC, false) })
        runCatching { meta.persistentDataContainer.set(toolKey, PersistentDataType.STRING, action) }
        stack.itemMeta = meta
        return stack
    }

    private fun toolAction(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR) return null
        val meta = item.itemMeta ?: return null
        val tag = runCatching { meta.persistentDataContainer.get(toolKey, PersistentDataType.STRING) }.getOrNull()
        if (tag != null) return tag
        bandFromDyeMaterial(item.type)?.let { return it.name }
        if (item.type == Material.BLACK_DYE || item.type == Material.SHEARS) return "UNLINK"
        return null
    }

    private fun isToolItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return runCatching { meta.persistentDataContainer.has(toolKey, PersistentDataType.STRING) }.getOrDefault(false)
    }

    private fun restoreHotbar(player: Player, saved: Array<ItemStack?>?) {
        if (saved == null) return
        val inv = player.inventory
        for (slot in 0..8) {
            inv.setItem(slot, saved.getOrNull(slot))
        }
    }

    fun isLinking(player: Player): Boolean = pending.containsKey(player.uniqueId)

    fun beginLink(player: Player, jukebox: Block, unlink: Boolean = false, band: LightBand = LightBand.BASS, theatre: Boolean = false) {
        val lightMaterial = Material.matchMaterial("LIGHT")
        if (theatre && lightMaterial == null) {
            player.sendMessage(Component.text("Theatre lights require Minecraft 1.17 or newer.")); return
        }
        cancelLink(player, notify = false)
        val inv = player.inventory
        val savedHotbar = Array(9) { slot -> inv.getItem(slot)?.clone() }
        LightBand.entries.forEachIndexed { idx, b ->
            val name = Component.text("✦ ${b.label} ✦", bandTextColor(b))
            val lore = listOf(
                Component.text("Right-click a light block to set to ${b.label}", NamedTextColor.GRAY),
                Component.text("Sneak-right-click or click jukebox to finish", NamedTextColor.DARK_GRAY),
            )
            inv.setItem(idx, createToolItem(b.name, bandDyeMaterial(b), name, lore))
        }
        val unlinkItem = createToolItem(
            "UNLINK",
            Material.BLACK_DYE,
            Component.text("✦ Unlink Light / Finish ✦", NamedTextColor.RED),
            listOf(
                Component.text("Right-click a linked light to unlink it", NamedTextColor.GRAY),
                Component.text("Sneak-right-click or click jukebox to finish", NamedTextColor.DARK_GRAY),
            )
        )
        inv.setItem(8, unlinkItem)
        if (theatre) {
            for (slot in 0..7) inv.setItem(slot, null)
            inv.setItem(0, createToolItem("THEATRE", lightMaterial!!,
                Component.text("Theatre light — place / link", NamedTextColor.GOLD),
                listOf(Component.text("Right-click to place a free light or link an existing light"),
                    Component.text("Click the jukebox or sneak-click air to finish"))))
            inv.setItem(8, createToolItem("UNLINK", lightMaterial,
                Component.text("Unlink theatre light", NamedTextColor.RED),
                listOf(Component.text("Right-click a light to unlink it"))))
        }
        inv.heldItemSlot = if (unlink) 8 else band.ordinal.coerceIn(0, 7)
        pending[player.uniqueId] = Pending(jukebox, theatre, unlink, band, savedHotbar)
        showHighlights(jukebox, durationMs = 180_000L)
        player.sendMessage(
            Component.text(
                if (theatre) "Right-click a surface or air to place a free theatre light, or an existing light to link it. Slot 9 unlinks. Click the jukebox or sneak-click air to finish." else "Equipped light dyes in your hotbar! Select a dye and right-click lights within 32 blocks to set their type (slot 9 unlinks). Sneak-right-click or click the jukebox when done.",
                NamedTextColor.GOLD
            )
        )
    }

    fun cancelLink(player: Player, notify: Boolean = false) {
        val removed = pending.remove(player.uniqueId) ?: return
        restoreHotbar(player, removed.savedHotbar)
        hideHighlights(removed.jukebox)
        if (notify) {
            player.sendMessage(Component.text("Finished configuring lights.", NamedTextColor.GREEN))
        }
    }
    fun linkedPositions(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty().map { it.lamp }
    fun theatrePositions(jukebox: Block) = linkedLights(jukebox).filter { it.theatre }.map { it.lamp }
    fun linkedBands(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty().map { it.band ?: LightBand.BASS }
    fun linkedLights(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty()
    fun restoreLinks(jukebox: Block, lamps: List<Position>, bands: List<LightBand> = emptyList(), theatre: List<Position> = emptyList()) {
        val source = Position.of(jukebox)
        var changed = false
        for ((index, lamp) in lamps.withIndex()) {
            if (lamp.world != source.world) continue
            val distance = (lamp.x.toLong() - source.x).let { it * it } + (lamp.y.toLong() - source.y).let { it * it } + (lamp.z.toLong() - source.z).let { it * it }
            if (distance > 1024 && lamp !in theatre) continue
            if (links[lamp]?.jukebox?.let { it != source } == true) continue
            val existing = links[lamp]
            val original = existing?.originalData ?: lamp.loadedBlock()?.blockData?.takeIf { if (lamp in theatre) it.material.name == "LIGHT" else LightBlock.supports(it) }?.asString ?: continue
            val link = Link(lamp, source, bands.getOrElse(index) { LightBand.BASS }, original, existing?.shutterData, lamp in theatre)
            if (links.put(lamp, link) != link) changed = true
        }
        if (changed) save()
    }
    fun count(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)]?.size ?: 0

    fun unlinkAll(jukebox: Block): Int = unsetAll(jukebox)

    fun unsetAll(jukebox: Block): Int {
        hideHighlights(jukebox)
        val target = Position.of(jukebox)
        pending.entries.removeIf { (uuid, req) ->
            if (Position.of(req.jukebox) == target) {
                Bukkit.getPlayer(uuid)?.let { restoreHotbar(it, req.savedHotbar) }
                true
            } else false
        }
        envelopes.keys.removeIf { it.jukebox == target }
        bandHistories.keys.removeIf { it.jukebox == target }
        var count = 0
        for (link in links.values.toList()) {
            if (link.jukebox == target) {
                if (links.remove(link.lamp, link)) {
                    litLamps.remove(link.lamp)
                    link.lamp.loadedBlock()?.let { restore(it, link) }
                    count++
                }
            }
        }
        if (count > 0) save()
        return count
    }

    private fun canConfigure(player: Player) =
        (player.hasPermission("pv.addon.discs.control") || player.hasPermission("vc.addon.discs.control")) &&
        (player.hasPermission("pv.addon.discs.play") || player.hasPermission("vc.addon.discs.play"))

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val request = pending[player.uniqueId] ?: return
        // Vanilla marks air interactions as cancelled when only block use is denied.
        if (event.useItemInHand() == Event.Result.DENY ||
            (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY)) return
        if (System.currentTimeMillis() > request.expires) {
            cancelLink(player, notify = true)
            return
        }
        val rightClick = event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR
        if (!rightClick) return
        var lamp = event.clickedBlock
        if (lamp == request.jukebox) {
            cancelLink(player, notify = true)
            return
        }
        val heldAction = toolAction(player.inventory.itemInMainHand)
        if (request.theatre && heldAction == "THEATRE" && !player.isSneaking && lamp?.type?.name != "LIGHT") {
            event.isCancelled = true
            val target = lamp?.getRelative(event.blockFace)
                ?: player.eyeLocation.let { it.add(it.direction.multiply(4.0)).block }
            if (count(request.jukebox) >= 1024) {
                player.sendMessage(Component.text("This jukebox already has 1,024 linked lights.")); return
            }
            val minHeight = runCatching { target.world.javaClass.getMethod("getMinHeight").invoke(target.world) as Int }.getOrDefault(0)
            if (target.y !in minHeight until target.world.maxHeight || !target.type.isAir || target.world != request.jukebox.world) return
            if (!canConfigure(player)) { cancelLink(player); return }
            val replaced = target.state
            target.setBlockData(Bukkit.createBlockData("minecraft:light[level=15]"), false)
            val placement = BlockPlaceEvent(target, replaced, lamp ?: target.getRelative(org.bukkit.block.BlockFace.DOWN), player.inventory.itemInMainHand, player, true, EquipmentSlot.HAND)
            Bukkit.getPluginManager().callEvent(placement)
            if (placement.isCancelled || !placement.canBuild()) {
                replaced.update(true, false)
                return
            }
            lamp = target
        }
        if (player.isSneaking && (lamp == null || (!links.containsKey(Position.of(lamp)) && !(if (request.theatre) lamp.type.name == "LIGHT" else LightBlock.supports(lamp.blockData))))) {
            event.isCancelled = true
            cancelLink(player, notify = true)
            return
        }
        if (lamp == null) {
            if (heldAction != null) event.isCancelled = true
            return
        }
        if (!links.containsKey(Position.of(lamp)) && !(if (request.theatre) lamp.type.name == "LIGHT" else LightBlock.supports(lamp.blockData))) {
            if (heldAction != null) event.isCancelled = true
            return
        }
        if (!(player.hasPermission("pv.addon.discs.control") || player.hasPermission("vc.addon.discs.control")) ||
            !(player.hasPermission("pv.addon.discs.play") || player.hasPermission("vc.addon.discs.play"))) {
            cancelLink(player)
            return
        }
        if (lamp.world != request.jukebox.world || (!request.theatre && lamp.location.distanceSquared(request.jukebox.location) > 32 * 32) ||
            (!request.theatre && request.jukebox.type != Material.JUKEBOX)) {
            event.isCancelled = true
            player.sendMessage(Component.text(if (request.theatre) "Choose a light in the same world as the jukebox." else "Choose a supported light block within 32 blocks of the jukebox."))
            return
        }
        if (!request.theatre && lamp.type.name == "LIGHT") return
        val unlink = heldAction == "UNLINK" || (heldAction == null && request.unlink)
        val band = heldAction?.let { act -> LightBand.entries.firstOrNull { it.name == act } } ?: request.band
        if (!unlink && lamp.type == Material.BEACON && links[Position.of(lamp)]?.shutterData == null &&
            BeaconShutter.capture(lamp) == null) {
            event.isCancelled = true
            player.sendMessage(Component.text("Leave the block directly above the beacon empty for its beam shutter."))
            return
        }
        event.isCancelled = true
        val key = Position.of(lamp)
        if (!unlink && links[key]?.jukebox?.let { it != Position.of(request.jukebox) } == true) {
            player.sendMessage(Component.text("That light is already linked to another jukebox.")); return
        }
        if (!unlink && !links.containsKey(key) && count(request.jukebox) >= 1024) {
            player.sendMessage(Component.text("This jukebox already has 1,024 linked lights.")); return
        }
        if (unlink) {
            if (links[key]?.jukebox != Position.of(request.jukebox)) {
                player.sendMessage(Component.text("That lamp is not linked to this jukebox.")); return
            }
            val removed = links.remove(key)
            litLamps.remove(key)
            removed?.let { restore(lamp, it) }
        } else {
            val original = links[key]?.originalData ?: lamp.blockData.asString
            val shutter = links[key]?.shutterData ?: if (lamp.type == Material.BEACON) BeaconShutter.capture(lamp)?.asString else null
            theatreFades.remove(key)
            links[key] = Link(key, Position.of(request.jukebox), band, original, shutter, request.theatre)
            litLamps.remove(key)
        }
        save()
        val remaining = count(request.jukebox)
        if (player.isSneaking) {
            cancelLink(player, notify = false)
            player.sendMessage(Component.text(if (unlink) "Light unlinked (finished)." else "Light set to ${if (request.theatre) "theatre" else band.label.lowercase()} (finished).", NamedTextColor.GREEN))
        } else {
            pending[player.uniqueId] = request.copy(expires = System.currentTimeMillis() + 180_000)
            if (remaining > 0) showHighlights(request.jukebox, durationMs = 180_000L) else hideHighlights(request.jukebox)
            player.sendMessage(Component.text(if (unlink) "Light unlinked ($remaining linked). Sneak-click or click jukebox when done." else "Light set to ${if (request.theatre) "theatre" else band.label} ($remaining linked).", bandTextColor(band)))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDropTool(event: PlayerDropItemEvent) {
        if (pending.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapTool(event: PlayerSwapHandItemsEvent) {
        if (pending.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClickTool(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (pending.containsKey(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDragTool(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (pending.containsKey(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeathTool(event: PlayerDeathEvent) {
        val req = pending.remove(event.entity.uniqueId) ?: return
        event.drops.removeIf(::isToolItem)
        req.savedHotbar?.filterNotNull()?.filter { it.type != Material.AIR }?.forEach { event.drops.add(it) }
        hideHighlights(req.jukebox)
    }

    fun start() {
        if (file.exists()) runCatching {
            gson.fromJson(file.readText(), Array<Link>::class.java)?.forEach { links[it.lamp] = it }
        }.onFailure { plugin.slF4JLogger.error("Could not load light links", it) }
        linksVersion.incrementAndGet()
        playerEffects.setLinks(links.values.toList())
        playerEffects.start()
        updater = scope.launch {
            var nextMaintenance = 0L
            var validSources: Map<Position, Boolean> = emptyMap()
            while (isActive) {
                val updateStarted = System.nanoTime()
                val snapshot = linkedIndex()
                val maintenance = System.nanoTime() >= nextMaintenance
                if (maintenance) {
                    val nearby = snapshot.sources.values.flatten().flatMap { link ->
                        PlayerAudience.nearby(link.lamp.world, link.lamp.x.toDouble(), link.lamp.y.toDouble(), link.lamp.z.toDouble(), 64.0)
                    }.distinctBy { it.uniqueId }
                    for (player in nearby) {
                        if (!PlayerAudience.canReceiveScreen(player) || !playerEffects.needsWarning(player)) continue
                        launch { suspendSyncFor(player, voiceServer, plugin) { playerEffects.showWarning(player) } }
                    }
                    val valid = ConcurrentHashMap<Position, Boolean>()
                    // Validate each source once on its owning region, never from a lamp region.
                    coroutineScope {
                        for ((chunk, sources) in snapshot.sources.keys.groupBy { ChunkKey(it.world, it.x shr 4, it.z shr 4) }) {
                            val world = runCatching { Bukkit.getWorld(UUID.fromString(chunk.world)) }.getOrNull() ?: continue
                            if (!world.isChunkLoaded(chunk.x, chunk.z)) continue
                            val first = sources.first()
                            launch {
                                runCatching {
                                    suspendSyncAt(Location(world, first.x.toDouble(), first.y.toDouble(), first.z.toDouble()), voiceServer, plugin) {
                                        for (source in sources) source.loadedBlock()?.let { valid[source] = it.type == Material.JUKEBOX }
                                    }
                                }
                            }
                        }
                    }
                    validSources = valid
                    nextMaintenance = System.nanoTime() + 3_000_000_000L
                }
                val sourceControls = jukeboxes.controlsByBlock.entries.associate { Position.of(it.key) to it.value }
                // One coarse audience query per playing jukebox; lamps test immutable positions.
                val audiences = snapshot.sources.keys.filter { sourceControls.containsKey(it) }.associateWith {
                    PlayerAudience.positions(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), 64.0)
                }

                // Precompute the synchronized target state and history for each active jukebox and band.
                // This ensures all lamps across all chunks on the same jukebox band receive the exact
                // same state at the exact same audio frame, while supporting distance-delayed wave mode
                // and alternating beat-chase mode.
                val activeJukeboxes = sourceControls.keys
                envelopes.keys.removeIf { it.jukebox !in activeJukeboxes }
                bandHistories.keys.removeIf { it.jukebox !in activeJukeboxes }
                for ((sourcePos, controls) in sourceControls) {
                    if (controls.paused) {
                        envelopes.keys.removeIf { it.jukebox == sourcePos }
                        bandHistories.keys.removeIf { it.jukebox == sourcePos }
                        continue
                    }
                    val falloff = effectiveFalloff(sourcePos, controls)
                    val levels = controls.spectrum.levels
                    val nonFullBands = LightBand.entries.filter { it != LightBand.FULL }
                    val dominantBand = nonFullBands.maxByOrNull { it.level(levels) } ?: LightBand.BASS
                    for (band in LightBand.entries) {
                        val key = SourceBandKey(sourcePos, band)
                        val env = envelopes.computeIfAbsent(key) { BassLampEnvelope() }
                        val lit = env.update(band.level(levels), falloff)
                        val effectiveColorBand = if (band == LightBand.FULL) dominantBand else band
                        val colorStep = LightBlock.bandColorOffset(effectiveColorBand) + env.beatCount
                        val state = BandTickState(lit = lit, beatCount = env.beatCount, isBuzzing = env.isBuzzing, colorStep = colorStep)
                        val history = bandHistories.computeIfAbsent(key) { ArrayDeque(16) }
                        synchronized(history) {
                            history.addFirst(state)
                            while (history.size > 16) history.removeLast()
                        }
                    }
                }

                // Assign deterministic parity indices (0 or 1) per (jukebox, band) for Beat Chase mode.
                val lampParities = HashMap<Position, Int>()
                val bandGroupSizes = HashMap<SourceBandKey, Int>()
                for ((sourcePos, jukeLinks) in snapshot.sources) {
                    for ((band, group) in jukeLinks.groupBy { it.band ?: LightBand.BASS }) {
                        val sorted = group.sortedWith(compareBy({ it.lamp.x }, { it.lamp.y }, { it.lamp.z }))
                        bandGroupSizes[SourceBandKey(sourcePos, band)] = sorted.size
                        sorted.forEachIndexed { idx, l -> lampParities[l.lamp] = idx and 1 }
                    }
                }

                val active = ConcurrentHashMap.newKeySet<PlaybackControls>()
                val dirty = AtomicBoolean(false)
                val chunksToUpdate = snapshot.chunks.filter { (_, chunkLinks) ->
                    maintenance || chunkLinks.any { sourceControls.containsKey(it.jukebox) || litLamps.contains(it.lamp) }
                }

                coroutineScope {
                    for ((chunk, chunkLinks) in chunksToUpdate) {
                        val world = runCatching { Bukkit.getWorld(UUID.fromString(chunk.world)) }.getOrNull() ?: continue
                        if (!world.isChunkLoaded(chunk.x, chunk.z)) continue
                        val first = chunkLinks.first().lamp
                        launch {
                            runCatching {
                                suspendSyncAt(Location(world, first.x.toDouble(), first.y.toDouble(), first.z.toDouble()), voiceServer, plugin) {
                                    for (link in chunkLinks) {
                                        if (links[link.lamp] != link) continue
                                        val lamp = link.lamp.loadedBlock() ?: continue
                                        if (!matches(lamp, link) || (maintenance && validSources[link.jukebox] == false)) {
                                            if (links.remove(link.lamp, link)) {
                                                litLamps.remove(link.lamp)
                                                dirty.set(true)
                                            }
                                            restore(lamp, link)
                                            continue
                                        }
                                        val controls = sourceControls[link.jukebox]
                                        if (link.theatre) {
                                            val data = lamp.blockData as? Levelled ?: continue
                                            val brightness = (original(link) as? Levelled)?.level ?: 15
                                            val playing = controls != null && !controls.paused &&
                                                (controls.sentAudioMs > 0L || controls.transitionPartner?.let { !it.paused && it.sentAudioMs > 0L } == true)
                                            val fade = theatreFades.computeIfAbsent(link.lamp) { TheatreLightFade(data.level, updateStarted) }
                                            val level = fade.update(playing, brightness, updateStarted)
                                            if (data.level != level) lamp.setBlockData(data.apply { this.level = level }, false)
                                            // Keep scheduling while dark or fading back up, even after playback disappears.
                                            if (playing || level != brightness) litLamps.add(link.lamp) else litLamps.remove(link.lamp)
                                            continue
                                        }
                                        val listening = controls != null && audiences[link.jukebox].orEmpty().any {
                                            it.within(link.lamp.x.toDouble(), link.lamp.y.toDouble(), link.lamp.z.toDouble(), 32.0)
                                        }
                                        if (listening) active.add(controls)
                                        val band = link.band ?: LightBand.BASS
                                        val key = SourceBandKey(link.jukebox, band)
                                        val history = bandHistories[key]
                                        val delayTicks = if (waveModes.contains(link.jukebox)) {
                                            val dx = (link.lamp.x - link.jukebox.x).toDouble()
                                            val dy = (link.lamp.y - link.jukebox.y).toDouble()
                                            val dz = (link.lamp.z - link.jukebox.z).toDouble()
                                            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                                            (dist / 3.0).toInt().coerceIn(0, 12)
                                        } else 0
                                        val state = if (history != null) {
                                            synchronized(history) {
                                                if (history.isEmpty()) BandTickState()
                                                else history[delayTicks.coerceAtMost(history.size - 1)]
                                            }
                                        } else BandTickState()
                                        val parity = lampParities[link.lamp] ?: 0
                                        val useChase = chaseModes.contains(link.jukebox) && (bandGroupSizes[key] ?: 0) >= 2
                                        val effectiveLit = if (!listening || controls.paused) {
                                            false
                                        } else if (!useChase) {
                                            state.lit
                                        } else if (state.isBuzzing) {
                                            if (parity == 0) state.lit else !state.lit
                                        } else {
                                            state.lit && ((state.beatCount and 1) == parity)
                                        }
                                        val colorStep = state.colorStep + parity
                                        if (effectiveLit) litLamps.add(link.lamp) else litLamps.remove(link.lamp)
                                        setLit(lamp, link, effectiveLit, colorStep)
                                    }
                                }
                            }
                        }
                    }
                }
                if (dirty.get()) save()
                val autoFalloffControls = sourceControls.filter { !falloffs.containsKey(it.key) }.values.toSet()
                autoFalloffControls.forEach { it.tempoAnalysis = true }
                active.forEach { it.tempoAnalysis = true }
                active.filterNot { it in consumers }.forEach { it.setVisualizing(true, "bass_lamps") }
                consumers.filterNot { it in active }.forEach {
                    it.setVisualizing(false, "bass_lamps")
                    if (it !in autoFalloffControls) it.tempoAnalysis = false
                }
                consumers.clear(); consumers.addAll(active)
                val elapsedMs = (System.nanoTime() - updateStarted) / 1_000_000L
                delay((50L - elapsedMs).coerceAtLeast(1L))
            }
        }
    }

    private fun original(link: Link) = LightBlock.original(link.originalData)
        ?: Bukkit.createBlockData(Material.REDSTONE_LAMP)
    private fun matches(block: Block, link: Link) =
        if (link.theatre) block.type.name == "LIGHT" else LightBlock.matches(block, original(link))
    private fun setLit(block: Block, link: Link, lit: Boolean, colorStep: Int = 0) {
        val original = original(link)
        if (original.material != Material.BEACON) {
            LightBlock.update(block, original, lit, colorStep)
            return
        }
        // Recover beacons darkened by older versions once, then leave the beacon untouched.
        if (block.type == Material.BLACK_CONCRETE) LightBlock.restore(block, original)
        if (block.type != Material.BEACON) return
        var shutter = link.shutterData
        if (shutter == null) {
            shutter = BeaconShutter.capture(block)?.asString ?: return
            if (!links.replace(link.lamp, link, link.copy(shutterData = shutter))) return
            save()
        }
        val empty = LightBlock.original(shutter) ?: return
        val litData = runCatching { Bukkit.createBlockData(BeaconShutter.glassForStep(colorStep)) }.getOrNull() ?: empty
        BeaconShutter.update(block, empty, Bukkit.createBlockData(Material.BLACK_CONCRETE), lit, litData)
    }
    private fun restore(block: Block, link: Link) {
        theatreFades.remove(link.lamp)
        if (link.theatre && !matches(block, link)) return
        val original = original(link)
        if (original.material == Material.BEACON) {
            link.shutterData?.let(LightBlock::original)?.let {
                BeaconShutter.update(block, it, Bukkit.createBlockData(Material.BLACK_CONCRETE), true, it)
            }
        }
        LightBlock.restore(block, original)
    }
    private fun save() {
        linksVersion.incrementAndGet()
        val snapshot = links.values.toList()
        playerEffects.setLinks(snapshot)
        ca.saboor.discs.utils.SettingsFileWriter.shared.submit(file, { gson.toJson(snapshot) }) {
            plugin.slF4JLogger.error("Could not save light links", it)
        }
    }
    private fun unlinkBroken(blocks: Collection<Block>) {
        val broken = blocks.map(Position::of).toSet()
        broken.forEach {
            falloffs.remove(it)
            waveModes.remove(it)
            chaseModes.remove(it)
            envelopes.keys.removeIf { key -> key.jukebox == it }
            bandHistories.keys.removeIf { key -> key.jukebox == it }
            highlightJobs.remove(it)?.cancel()
            highlights.remove(it)?.forEach { h -> safeRemove(h.blockDisplay); safeRemove(h.textDisplay) }
        }
        var changed = false
        for (link in links.values.toList()) if (link.lamp in broken || link.jukebox in broken ||
            (link.shutterData != null && link.lamp.copy(y = link.lamp.y + 1) in broken)) {
            links.remove(link.lamp, link); litLamps.remove(link.lamp)
            link.lamp.loadedBlock()?.let { restore(it, link) }
            changed = true
        }
        if (changed) save()
    }
    private fun isShutter(block: Block): Boolean {
        val position = Position.of(block)
        return (block.type == Material.BLACK_CONCRETE || BeaconShutter.isShutterGlass(block.type)) &&
            links[position.copy(y = position.y - 1)]?.shutterData != null
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onShutterPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any(::isShutter)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onShutterPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any(::isShutter)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onShutterBreak(event: BlockBreakEvent) {
        if (isShutter(event.block)) event.isDropItems = false
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val position = Position.of(event.block)
        if (event.block.type == Material.JUKEBOX || links.containsKey(position) ||
            links[position.copy(y = position.y - 1)]?.shutterData != null)
            unlinkBroken(listOf(event.block))
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) { unlinkBroken(event.blocks) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) { unlinkBroken(event.blocks) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) { unlinkBroken(event.blockList()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) { unlinkBroken(event.blockList()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        linkedIndex().chunks[ChunkKey(event.world.uid.toString(), event.chunk.x, event.chunk.z)].orEmpty()
            .forEach { link -> link.lamp.loadedBlock()?.let { block -> restore(block, link) }; litLamps.remove(link.lamp) }
        highlights.values.forEach { list ->
            list.removeIf { h ->
                val bIn = h.blockDisplay?.let { it.location.chunk == event.chunk } ?: false
                val tIn = h.textDisplay?.let { it.location.chunk == event.chunk } ?: false
                if (bIn || tIn) {
                    safeRemove(h.blockDisplay)
                    safeRemove(h.textDisplay)
                    true
                } else false
            }
        }
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { cancelLink(event.player) }
    fun shutdown() {
        updater?.cancel()
        playerEffects.shutdown()
        save()
        consumers.forEach { it.setVisualizing(false, "bass_lamps"); it.tempoAnalysis = false }; consumers.clear()
        links.values.forEach { link -> link.lamp.loadedBlock()?.let { block -> restore(block, link) } }
        highlightJobs.values.forEach { it.cancel() }; highlightJobs.clear()
        highlights.values.flatten().forEach { safeRemove(it.blockDisplay); safeRemove(it.textDisplay) }
        highlights.clear()
        pending.forEach { (uuid, req) -> Bukkit.getPlayer(uuid)?.let { restoreHotbar(it, req.savedHotbar) } }
        pending.clear(); envelopes.clear(); bandHistories.clear(); litLamps.clear(); theatreFades.clear()
    }
}
