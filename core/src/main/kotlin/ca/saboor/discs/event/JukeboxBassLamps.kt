package ca.saboor.discs.event

import ca.saboor.discs.PlaybackControls
import ca.saboor.discs.utils.PlayerAudience
import ca.saboor.discs.utils.extend.suspendSyncAt
import ca.saboor.discs.visualizer.BassLampEnvelope
import ca.saboor.discs.visualizer.BeaconShutter
import ca.saboor.discs.visualizer.LightBand
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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
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
    data class Link(val lamp: Position, val jukebox: Position, val band: LightBand? = LightBand.BASS, val originalData: String? = null, val shutterData: String? = null) {
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
    private data class Pending(val jukebox: Block, val unlink: Boolean, val band: LightBand = LightBand.BASS, val expires: Long = System.currentTimeMillis() + 120_000)
    private val plugin: JavaPlugin by inject()
    private val scope: CoroutineScope by inject()
    private val voiceServer: PlasmoVoiceServer? by getterOrNull()
    private val links = ConcurrentHashMap<Position, Link>()
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val falloffs = ConcurrentHashMap<Position, Double>()
    private val autoFalloffs = ConcurrentHashMap.newKeySet<Position>()
    fun isAutoFalloff(jukebox: Block): Boolean = autoFalloffs.contains(Position.of(jukebox))
    fun setAutoFalloff(jukebox: Block, auto: Boolean = true) {
        val pos = Position.of(jukebox)
        if (auto) autoFalloffs.add(pos) else autoFalloffs.remove(pos)
    }
    private fun effectiveFalloff(jukebox: Position, controls: PlaybackControls?): Double {
        if (autoFalloffs.contains(jukebox)) {
            val tempo = controls?.tempoFalloff()
            if (tempo != null) return tempo
        }
        return falloffs[jukebox] ?: 0.2
    }
    fun falloff(jukebox: Block): Double =
        effectiveFalloff(Position.of(jukebox), jukeboxes.controlsByBlock[jukebox])
    fun setFalloff(jukebox: Block, value: Double) {
        require(value.isFinite() && value in 0.0..1.0)
        autoFalloffs.remove(Position.of(jukebox))
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
            val glowColor = bandGlowColor(link.band ?: LightBand.BASS)
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
                .append(Component.text(band.label, bandTextColor(band)))
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
    private val envelopes = ConcurrentHashMap<SourceBandKey, BassLampEnvelope>()
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

    fun beginLink(player: Player, jukebox: Block, unlink: Boolean = false, band: LightBand = LightBand.BASS) {
        pending[player.uniqueId] = Pending(jukebox, unlink, band)
        player.sendMessage(Component.text("Right-click a solid light-emitting block within 32 blocks of this jukebox to ${if (unlink) "unlink" else "link"} it (2 minutes)."))
    }
    fun linkedPositions(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty().map { it.lamp }
    fun linkedBands(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty().map { it.band ?: LightBand.BASS }
    fun linkedLights(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)].orEmpty()
    fun restoreLinks(jukebox: Block, lamps: List<Position>, bands: List<LightBand> = emptyList()) {
        val source = Position.of(jukebox)
        var changed = false
        for ((index, lamp) in lamps.withIndex()) {
            if (lamp.world != source.world) continue
            val distance = (lamp.x.toLong() - source.x).let { it * it } + (lamp.y.toLong() - source.y).let { it * it } + (lamp.z.toLong() - source.z).let { it * it }
            if (distance > 1024) continue
            if (links[lamp]?.jukebox?.let { it != source } == true) continue
            val existing = links[lamp]
            val original = existing?.originalData ?: lamp.loadedBlock()?.blockData?.takeIf(LightBlock::supports)?.asString ?: continue
            val link = Link(lamp, source, bands.getOrElse(index) { LightBand.BASS }, original, existing?.shutterData)
            if (links.put(lamp, link) != link) changed = true
        }
        if (changed) save()
    }
    fun count(jukebox: Block) = linkedIndex().sources[Position.of(jukebox)]?.size ?: 0

    fun unlinkAll(jukebox: Block): Int = unsetAll(jukebox)

    fun unsetAll(jukebox: Block): Int {
        hideHighlights(jukebox)
        val target = Position.of(jukebox)
        pending.values.removeIf { Position.of(it.jukebox) == target }
        envelopes.keys.removeIf { it.jukebox == target }
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val request = pending[event.player.uniqueId] ?: return
        val lamp = event.clickedBlock ?: return
        if (!links.containsKey(Position.of(lamp)) && !LightBlock.supports(lamp.blockData)) return
        if (System.currentTimeMillis() > request.expires) { pending.remove(event.player.uniqueId); return }
        val player = event.player
        if (!(player.hasPermission("pv.addon.discs.control") || player.hasPermission("vc.addon.discs.control")) ||
            !(player.hasPermission("pv.addon.discs.play") || player.hasPermission("vc.addon.discs.play"))) return
        if (lamp.world != request.jukebox.world || lamp.location.distanceSquared(request.jukebox.location) > 32 * 32 ||
            request.jukebox.type != Material.JUKEBOX) {
            player.sendMessage(Component.text("Choose a solid light-emitting block within 32 blocks of the jukebox."))
            return
        }
        if (!request.unlink && lamp.type == Material.BEACON && links[Position.of(lamp)]?.shutterData == null &&
            BeaconShutter.capture(lamp) == null) {
            event.isCancelled = true
            player.sendMessage(Component.text("Leave the block directly above the beacon empty for its beam shutter."))
            return
        }
        event.isCancelled = true
        pending.remove(player.uniqueId)
        val key = Position.of(lamp)
        if (request.unlink) {
            if (links[key]?.jukebox != Position.of(request.jukebox)) {
                player.sendMessage(Component.text("That lamp is not linked to this jukebox.")); return
            }
            val removed = links.remove(key)
            litLamps.remove(key)
            removed?.let { restore(lamp, it) }
        } else {
            val original = links[key]?.originalData ?: lamp.blockData.asString
            val shutter = links[key]?.shutterData ?: if (lamp.type == Material.BEACON) BeaconShutter.capture(lamp)?.asString else null
            links[key] = Link(key, Position.of(request.jukebox), request.band, original, shutter)
            litLamps.remove(key)
        }
        save()
        if (isHighlighting(request.jukebox)) {
            if (count(request.jukebox) > 0) showHighlights(request.jukebox)
            else hideHighlights(request.jukebox)
        }
        player.sendMessage(Component.text(if (request.unlink) "Light unlinked." else "Light set to ${request.band.label.lowercase()}."))
    }

    fun start() {
        if (file.exists()) runCatching {
            gson.fromJson(file.readText(), Array<Link>::class.java)?.forEach { links[it.lamp] = it }
        }.onFailure { plugin.slF4JLogger.error("Could not load light links", it) }
        linksVersion.incrementAndGet()
        updater = scope.launch {
            var nextMaintenance = 0L
            var validSources: Map<Position, Boolean> = emptyMap()
            while (isActive) {
                val updateStarted = System.nanoTime()
                val snapshot = linkedIndex()
                val maintenance = System.nanoTime() >= nextMaintenance
                if (maintenance) {
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

                // Precompute the synchronized target lit state for each active jukebox and band.
                // This ensures all lamps across all chunks on the same jukebox band receive the exact
                // same state at the exact same audio frame, eliminating inter-chunk envelope divergence.
                val targetLitStates = mutableMapOf<SourceBandKey, Boolean>()
                val activeJukeboxes = sourceControls.keys
                envelopes.keys.removeIf { it.jukebox !in activeJukeboxes }
                for ((sourcePos, controls) in sourceControls) {
                    if (controls.paused) {
                        envelopes.keys.removeIf { it.jukebox == sourcePos }
                        continue
                    }
                    val falloff = effectiveFalloff(sourcePos, controls)
                    val levels = controls.spectrum.levels
                    for (band in LightBand.entries) {
                        val key = SourceBandKey(sourcePos, band)
                        val env = envelopes.computeIfAbsent(key) { BassLampEnvelope() }
                        targetLitStates[key] = env.update(band.level(levels), falloff)
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
                                        val listening = controls != null && audiences[link.jukebox].orEmpty().any {
                                            it.within(link.lamp.x.toDouble(), link.lamp.y.toDouble(), link.lamp.z.toDouble(), 32.0)
                                        }
                                        if (listening) active.add(controls)
                                        val band = link.band ?: LightBand.BASS
                                        val lit = listening && !controls.paused && (targetLitStates[SourceBandKey(link.jukebox, band)] == true)
                                        if (lit) litLamps.add(link.lamp) else litLamps.remove(link.lamp)
                                        setLit(lamp, link, lit)
                                    }
                                }
                            }
                        }
                    }
                }
                if (dirty.get()) save()
                val autoFalloffControls = sourceControls.filter { autoFalloffs.contains(it.key) }.values.toSet()
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
    private fun matches(block: Block, link: Link) = LightBlock.matches(block, original(link))
    private fun setLit(block: Block, link: Link, lit: Boolean) {
        val original = original(link)
        if (original.material != Material.BEACON) {
            LightBlock.update(block, original, lit)
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
        BeaconShutter.update(block, empty, Bukkit.createBlockData(Material.BLACK_CONCRETE), lit)
    }
    private fun restore(block: Block, link: Link) {
        val original = original(link)
        if (original.material == Material.BEACON) {
            link.shutterData?.let(LightBlock::original)?.let {
                BeaconShutter.update(block, it, Bukkit.createBlockData(Material.BLACK_CONCRETE), true)
            }
        }
        LightBlock.restore(block, original)
    }
    private fun save() {
        linksVersion.incrementAndGet()
        val snapshot = links.values.toList()
        ca.saboor.discs.utils.SettingsFileWriter.shared.submit(file, { gson.toJson(snapshot) }) {
            plugin.slF4JLogger.error("Could not save light links", it)
        }
    }
    private fun unlinkBroken(blocks: Collection<Block>) {
        val broken = blocks.map(Position::of).toSet()
        broken.forEach {
            falloffs.remove(it)
            autoFalloffs.remove(it)
            envelopes.keys.removeIf { key -> key.jukebox == it }
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
        return block.type == Material.BLACK_CONCRETE &&
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
    @EventHandler fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
    fun shutdown() {
        updater?.cancel()
        save()
        consumers.forEach { it.setVisualizing(false, "bass_lamps"); it.tempoAnalysis = false }; consumers.clear()
        links.values.forEach { link -> link.lamp.loadedBlock()?.let { block -> restore(block, link) } }
        highlightJobs.values.forEach { it.cancel() }; highlightJobs.clear()
        highlights.values.flatten().forEach { safeRemove(it.blockDisplay); safeRemove(it.textDisplay) }
        highlights.clear()
        pending.clear(); envelopes.clear(); litLamps.clear()
    }
}
