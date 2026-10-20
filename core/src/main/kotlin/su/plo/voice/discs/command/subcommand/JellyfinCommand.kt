package su.plo.voice.discs.command.subcommand

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.inject
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.AddonKeys
import su.plo.voice.discs.api.PlayerBurnEvent
import su.plo.voice.discs.api.PrePlayerBurnEvent
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.item.DiscHelper
import su.plo.voice.discs.jellyfin.*
import su.plo.voice.discs.utils.extend.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Credentials are collected in cancelled private chat, never as command arguments. */
class JellyfinCommand(private val library: JellyfinLibrary) : SubCommand(), Listener {
    private val discHelper: DiscHelper by inject()
    private val client = JellyfinClient()
    private enum class Step { URL, USER, PASSWORD, SEARCH }
    private class Prompt(val step: Step, val url: String = "", val user: String = "", val expires: Long = System.currentTimeMillis() + 120_000)
    private val prompts = ConcurrentHashMap<UUID, Prompt>()
    private val busy = ConcurrentHashMap.newKeySet<UUID>()
    private val pageLoading = ConcurrentHashMap.newKeySet<UUID>()
    private val requests = ConcurrentHashMap<UUID, UUID>()
    private class Menu(val owner: UUID, val connection: JellyfinLibrary.Connection, val page: Int,
                       val search: String, val result: JellyfinClient.Page) : InventoryHolder {
        lateinit var view: Inventory
        override fun getInventory() = view
    }
    override val name = "jellyfin"
    override val permissions = listOf("jellyfin" to PermissionDefault.OP)
    private fun Player.allowed(suffix: String) = hasPermission("pv.addon.discs.$suffix") || hasPermission("vc.addon.discs.$suffix")
    override fun checkCanExecute(sender: CommandSender) =
        (sender.hasPermission("pv.addon.discs.jellyfin") || sender.hasPermission("vc.addon.discs.jellyfin")) &&
        (sender.hasPermission("pv.addon.discs.burn") || sender.hasPermission("vc.addon.discs.burn"))
    private fun message(player: Player, text: String, error: Boolean = false) =
        player.sendMessage(Component.text(text, if (error) NamedTextColor.RED else NamedTextColor.GREEN))
    override fun suggest(source: CommandSender, arguments: Array<out String>) =
        if (checkCanExecute(source) && arguments.size == 2)
            listOf("connect", "browse", "search", "disconnect", "cancel").filter { it.startsWith(arguments[1], true) } else emptyList()

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender as? Player ?: return
        if (!checkCanExecute(player)) { message(player, "No permission to use Jellyfin and burn discs.", true); return }
        when (arguments.getOrNull(1)?.lowercase()) {
            "cancel" -> {
                prompts.remove(player.uniqueId); requests.remove(player.uniqueId)
                message(player, "Jellyfin input cancelled.")
            }
            "disconnect" -> run(player) {
                val old = library.disconnect(player.uniqueId)
                if (old != null) runCatching { client.logout(old.session) }
                suspendSyncFor(player, voiceServer, plugin) {
                    player.closeInventory(); prompts.remove(player.uniqueId)
                    message(player, "Jellyfin disconnected. Connect to the same server/account again to use its discs.")
                }
            }
            "connect" -> {
                if (busy.contains(player.uniqueId)) { message(player, "A Jellyfin request is still running.", true); return }
                val url = arguments.getOrNull(2)
                if (arguments.size > 3) { message(player, "Usage: /disc jellyfin connect [server-url]. Credentials are requested privately.", true); return }
                try {
                    if (url == null) prompt(player, Prompt(Step.URL), "Enter your Jellyfin server URL in chat, or cancel.")
                    else prompt(player, Prompt(Step.USER, JellyfinClient.normalizeUrl(url)), "Enter your Jellyfin username in chat, or cancel.")
                } catch (e: Exception) { message(player, e.message ?: "Invalid server URL.", true) }
            }
            "search" -> browse(player, 0, arguments.drop(2).joinToString(" ").take(200))
            null, "browse" -> {
                if (library.connection(player.uniqueId) == null) prompt(player, Prompt(Step.URL), "Enter your Jellyfin server URL in chat, or cancel.")
                else browse(player, 0, "")
            }
            else -> message(player, "Usage: /disc jellyfin [connect [url]|browse|search <text>|disconnect|cancel]", true)
        }
    }
    private fun prompt(player: Player, value: Prompt, text: String) {
        prompts[player.uniqueId] = value
        message(player, "$text This message will not be broadcast. Input expires in 2 minutes.")
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.LOWEST)
    fun onInput(event: AsyncPlayerChatEvent) {
        val pendingInput = prompts[event.player.uniqueId] ?: return
        // Remove recipients too, so even a later cancellation reset cannot broadcast credentials.
        event.isCancelled = true
        event.recipients.clear()
        val text = event.message
        event.message = "[Jellyfin private input]"
        if (!prompts.remove(event.player.uniqueId, pendingInput)) return
        val player = event.player
        scope.launch {
            suspendSyncFor(player, voiceServer, plugin) {
                if (!checkCanExecute(player) || !player.isOnline) return@suspendSyncFor
                if (text.equals("cancel", true) || System.currentTimeMillis() > pendingInput.expires) {
                    message(player, "Jellyfin input cancelled or expired."); return@suspendSyncFor
                }
                when (pendingInput.step) {
                    Step.URL -> try {
                        prompt(player, Prompt(Step.USER, JellyfinClient.normalizeUrl(text)), "Enter your Jellyfin username in chat, or cancel.")
                    } catch (_: Exception) { message(player, "Invalid Jellyfin server URL. Run /disc jellyfin connect again.", true) }
                    Step.USER -> {
                        if (text.isBlank() || text.length > 200) { message(player, "Invalid username. Run /disc jellyfin connect again.", true); return@suspendSyncFor }
                        prompt(player, Prompt(Step.PASSWORD, pendingInput.url, text), "Enter your Jellyfin password in chat (use <empty> for no password), or cancel.")
                    }
                    Step.PASSWORD -> login(player, pendingInput.url, pendingInput.user, if (text == "<empty>") "" else text)
                    Step.SEARCH -> browse(player, 0, text.take(200))
                }
            }
        }
    }

    private fun run(player: Player, action: suspend () -> Unit) {
        if (!busy.add(player.uniqueId)) { message(player, "A Jellyfin request is still running.", true); return }
        scope.launch(Dispatchers.IO) {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                suspendSyncFor(player, voiceServer, plugin) {
                    if (player.isOnline) message(player, (e as? JellyfinException)?.message ?: "Jellyfin operation failed. Check the connection and try again.", true)
                }
            } finally { busy.remove(player.uniqueId) }
        }
    }
    private fun login(player: Player, url: String, user: String, password: String) {
        val request = UUID.randomUUID()
        requests[player.uniqueId] = request
        message(player, "Connecting to Jellyfin...")
        run(player) {
            val session = client.login(url, user, password, player.uniqueId.toString())
            if (requests[player.uniqueId] != request) { runCatching { client.logout(session) }; return@run }
            val old = library.connection(player.uniqueId)
            val connected = library.connect(player.uniqueId, session)
            if (old != null && old.session.token != session.token) runCatching { client.logout(old.session) }
            suspendSyncFor(player, voiceServer, plugin) {
                if (player.isOnline) { message(player, "Connected. Hold the disc you want to burn, then select a media item."); openPage(player, connected, 0, "") }
            }
        }
    }
    private fun browse(player: Player, page: Int, search: String) {
        val connection = library.connection(player.uniqueId) ?: run { message(player, "Connect first with /disc jellyfin connect.", true); return }
        openPage(player, connection, page, search)
    }
    private fun openPage(player: Player, connection: JellyfinLibrary.Connection, page: Int, search: String) {
        // Separate page loads from the connection job, which may still own the busy flag.
        if (!pageLoading.add(player.uniqueId)) return
        val request = UUID.randomUUID()
        requests[player.uniqueId] = request
        scope.launch(Dispatchers.IO) {
            try {
                val result = client.browse(connection.session, page, search)
                suspendSyncFor(player, voiceServer, plugin) {
                    if (!player.isOnline || !checkCanExecute(player) || requests[player.uniqueId] != request || library.connection(player.uniqueId) !== connection) return@suspendSyncFor
                    val menu = Menu(player.uniqueId, connection, page, search, result)
                    menu.view = Bukkit.createInventory(menu, 54, Component.text("Jellyfin · Page ${page + 1}"))
                    result.items.forEachIndexed { index, media ->
                        menu.view.setItem(index, icon(Material.MUSIC_DISC_CAT, media.label,
                            listOf(media.type, media.album, "${media.durationMs / 60_000}:${(media.durationMs / 1000 % 60).toString().padStart(2, '0')}", "Click to burn the disc in your main hand")))
                    }
                    if (page > 0) menu.view.setItem(45, icon(Material.ARROW, "Previous page"))
                    menu.view.setItem(47, icon(Material.COMPASS, "Search media"))
                    menu.view.setItem(49, icon(Material.BOOK, "${result.total} media items", listOf(search.ifEmpty { "All media" })))
                    menu.view.setItem(51, icon(Material.BARRIER, "Disconnect Jellyfin"))
                    if ((page + 1) * JellyfinClient.PAGE_SIZE < result.total) menu.view.setItem(53, icon(Material.ARROW, "Next page"))
                    player.openInventory(menu.view)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { suspendSyncFor(player, voiceServer, plugin) {
                if (player.isOnline && requests[player.uniqueId] == request) message(player, (e as? JellyfinException)?.message ?: "Could not load Jellyfin media.", true)
            } } finally { pageLoading.remove(player.uniqueId) }
        }
    }
    private fun icon(material: Material, title: String, lore: List<String> = emptyList()) = ItemStack(material).apply {
        editMeta { it.displayName(Component.text(title, NamedTextColor.GOLD)); it.lore(lore.filter { line -> line.isNotBlank() }.map { line -> Component.text(line, NamedTextColor.GRAY) }) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMenuClick(event: InventoryClickEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (menu.owner != player.uniqueId || !checkCanExecute(player) || library.connection(player.uniqueId) !== menu.connection) return
        val slot = event.rawSlot
        if (slot < 0 || slot >= 54) return
        scope.launch { suspendSyncFor(player, voiceServer, plugin) {
            if (player.openInventory.topInventory.holder !== menu) return@suspendSyncFor
            when {
                slot in menu.result.items.indices -> burn(player, menu.connection, menu.result.items[slot])
                slot == 45 && menu.page > 0 -> openPage(player, menu.connection, menu.page - 1, menu.search)
                slot == 53 && (menu.page + 1) * JellyfinClient.PAGE_SIZE < menu.result.total -> openPage(player, menu.connection, menu.page + 1, menu.search)
                slot == 47 -> { player.closeInventory(); prompt(player, Prompt(Step.SEARCH), "Enter a title or artist to search, or cancel.") }
                slot == 51 -> execute(player, arrayOf("jellyfin", "disconnect"))
            }
        } }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMenuDrag(event: InventoryDragEvent) { if (event.view.topInventory.holder is Menu) event.isCancelled = true }
    @EventHandler fun onMenuClose(event: InventoryCloseEvent) {
        if (event.inventory.holder is Menu) requests.remove(event.player.uniqueId)
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { prompts.remove(event.player.uniqueId); requests.remove(event.player.uniqueId) }

    private fun burn(player: Player, connection: JellyfinLibrary.Connection, media: JellyfinClient.Media) {
        val original = player.inventory.itemInMainHand.clone()
        val slot = player.inventory.heldItemSlot
        fun burnable(item: ItemStack) = discHelper.isRecord(item) && item.amount == 1 &&
            (!config.burnableTag.requireBurnableTag || item.itemMeta.persistentDataContainer.has(AddonKeys.burnableKey, PersistentDataType.BYTE) || player.allowed("burn.burnable_check_bypass"))
        if (!burnable(original)) { message(player, "Hold one burnable music disc in your main hand.", true); return }
        player.closeInventory()
        message(player, "Loading ${media.label}...")
        run(player) {
            val identifier = library.grant(connection, media)
            var burned = false
            try {
                val voicePlayer = voiceServer?.let { player.asVoicePlayer(it) }
                if (voicePlayer != null && !suspendSyncFor(player, voiceServer, plugin) { PrePlayerBurnEvent(voicePlayer, identifier).callEvent() }) return@run
                val track = withTimeout(90_000) { audioPlayerManager.getTrack(identifier).await() }
                suspendSyncFor(player, voiceServer, plugin) {
                    if (!player.isOnline || !checkCanExecute(player) || library.connection(player.uniqueId) !== connection ||
                        player.inventory.heldItemSlot != slot || player.inventory.itemInMainHand != original || !burnable(original)) {
                        message(player, "Your held disc or Jellyfin connection changed. Select the media item again.", true); return@suspendSyncFor
                    }
                    val item = original.clone()
                    if (voicePlayer != null && !PlayerBurnEvent(voicePlayer, track, item).callEvent()) return@suspendSyncFor
                    if (player.inventory.itemInMainHand != original) return@suspendSyncFor
                    if (Bukkit.getServer().getMinecraftVersionInt() >= 12103) discHelper.showSongTooltip(item, false)
                    item.burnMetadata(identifier, media.label, config, discHelper)
                    player.inventory.setItemInMainHand(item)
                    burned = true
                    message(player, "Disc burned: ${media.label}")
                }
            } finally { if (!burned) library.revoke(identifier) }
        }
    }
}
