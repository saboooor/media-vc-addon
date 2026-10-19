package su.plo.voice.discs.screen

import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.koin.core.component.inject
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.discs.command.SubCommand
import su.plo.voice.discs.utils.extend.asPlayer
import java.util.Locale

class ScreenCommand : SubCommand() {

    private val screenManager: ScreenManager by inject()

    override val name = "screen"

    override val permissions = listOf(
        "screen" to PermissionDefault.OP,
    )

    override fun suggest(source: CommandSender, arguments: Array<out String>): List<String> {
        val player = source.asPlayer() ?: return emptyList()
        if (!player.hasPermission("pv.addon.discs.screen") && !player.hasPermission("vc.addon.discs.screen") && !player.isOp) {
            return emptyList()
        }

        if (arguments.size == 2) {
            val sub = listOf("create", "delete", "link", "unlink", "list", "play", "stop", "clear", "cancel", "finish", "install-ffmpeg")
            return sub.filter { it.startsWith(arguments[1].lowercase(Locale.ROOT)) }
        }

        if (arguments.size == 3) {
            val action = arguments[1].lowercase(Locale.ROOT)
            if (action in listOf("delete", "link", "unlink", "play", "stop", "clear")) {
                val screens = screenManager.getAllScreens().map { it.name }
                return screens.filter { it.startsWith(arguments[2].lowercase(Locale.ROOT)) }
            }
        }

        return emptyList()
    }

    override fun execute(sender: CommandSender, arguments: Array<out String>) {
        val player = sender.asPlayer() ?: run {
            sender.sendMessage("Player only command")
            return
        }

        if (!player.hasPermission("pv.addon.discs.screen") && !player.hasPermission("vc.addon.discs.screen") && !player.isOp) {
            player.sendMessage(Component.text("You do not have permission to use screen commands.", NamedTextColor.RED))
            return
        }

        if (arguments.size < 2) {
            sendHelp(player)
            return
        }

        val sub = arguments[1].lowercase(Locale.ROOT)

        when (sub) {
            "create" -> {
                val name = if (arguments.size >= 3) {
                    arguments[2]
                } else {
                    var counter = 1
                    while (screenManager.getScreen("screen-$counter") != null) {
                        counter++
                    }
                    "screen-$counter"
                }
                screenManager.startSelection(player, name)
            }

            "cancel" -> {
                if (!screenManager.cancelSelection(player)) {
                    player.sendMessage(Component.text("You do not have an active screen selection.", NamedTextColor.YELLOW))
                }
            }

            "finish" -> {
                if (!screenManager.finishSelection(player)) {
                    player.sendMessage(Component.text("No active screen selection ready to finish.", NamedTextColor.YELLOW))
                }
            }

            "delete" -> {
                if (arguments.size < 3) {
                    player.sendMessage(Component.text("Usage: /disc screen delete <name>", NamedTextColor.RED))
                    return
                }
                val name = arguments[2]
                if (screenManager.deleteScreen(name)) {
                    player.sendMessage(Component.text("Screen '$name' was deleted.", NamedTextColor.GREEN))
                } else {
                    player.sendMessage(Component.text("Screen '$name' not found.", NamedTextColor.RED))
                }
            }

            "list" -> {
                val all = screenManager.getAllScreens()
                if (all.isEmpty()) {
                    player.sendMessage(Component.text("No item frame screens registered yet. Use /disc screen create to create one.", NamedTextColor.YELLOW))
                    return
                }
                player.sendMessage(Component.text("=== Registered Screens (${all.size}) ===", NamedTextColor.GOLD))
                for (s in all) {
                    val jbText = if (s.hasLinkedJukebox()) "Jukebox at (${s.jukeboxX}, ${s.jukeboxY}, ${s.jukeboxZ})" else "No jukebox linked"
                    player.sendMessage(
                        Component.text("• ", NamedTextColor.GRAY)
                            .append(Component.text(s.name, NamedTextColor.AQUA))
                            .append(Component.text(" - ${s.columns}x${s.rows} frames (${s.facing.name}) in ${s.worldName} | $jbText", NamedTextColor.WHITE))
                    )
                }
            }

            "link" -> {
                if (arguments.size < 3) {
                    player.sendMessage(Component.text("Usage: /disc screen link <name>", NamedTextColor.RED))
                    return
                }
                val name = arguments[2]
                val screen = screenManager.getScreen(name)
                if (screen == null) {
                    player.sendMessage(Component.text("Screen '$name' not found.", NamedTextColor.RED))
                    return
                }
                // Check block player is looking at
                val targetBlock = player.getTargetBlockExact(6)
                if (targetBlock != null && targetBlock.type == org.bukkit.Material.JUKEBOX) {
                    screenManager.linkJukebox(name, targetBlock)
                    player.sendMessage(Component.text("Screen '$name' linked to targeted jukebox at (${targetBlock.x}, ${targetBlock.y}, ${targetBlock.z})!", NamedTextColor.GREEN))
                } else {
                    player.sendMessage(Component.text("Look directly at a Jukebox within 6 blocks and run '/disc screen link $name'.", NamedTextColor.YELLOW))
                }
            }

            "unlink" -> {
                if (arguments.size < 3) {
                    player.sendMessage(Component.text("Usage: /disc screen unlink <name>", NamedTextColor.RED))
                    return
                }
                val name = arguments[2]
                if (screenManager.unlinkJukebox(name)) {
                    player.sendMessage(Component.text("Screen '$name' was unlinked and deleted.", NamedTextColor.GREEN))
                } else {
                    player.sendMessage(Component.text("Screen '$name' not found.", NamedTextColor.RED))
                }
            }

            "play" -> {
                if (arguments.size < 4) {
                    player.sendMessage(Component.text("Usage: /disc screen play <name> <url>", NamedTextColor.RED))
                    return
                }
                val name = arguments[2]
                val url = arguments[3]
                val screen = screenManager.getScreen(name)
                if (screen == null) {
                    player.sendMessage(Component.text("Screen '$name' not found.", NamedTextColor.RED))
                    return
                }
                player.sendMessage(Component.text("Starting video on screen '$name'...", NamedTextColor.YELLOW))
                screenManager.playVideo(screen, url)
            }

            "stop" -> {
                if (arguments.size < 3) {
                    screenManager.getAllScreens().forEach { screenManager.stopVideo(it) }
                    player.sendMessage(Component.text("Stopped all active screen video players.", NamedTextColor.GREEN))
                    return
                }
                val name = arguments[2]
                val screen = screenManager.getScreen(name)
                if (screen == null) {
                    player.sendMessage(Component.text("Screen '$name' not found.", NamedTextColor.RED))
                    return
                }
                screenManager.stopVideo(screen)
                player.sendMessage(Component.text("Stopped video on screen '$name'.", NamedTextColor.GREEN))
            }

            "clear" -> {
                if (arguments.size < 3) {
                    screenManager.getAllScreens().forEach { screenManager.clearScreen(it) }
                    player.sendMessage(Component.text("Cleared all screens.", NamedTextColor.GREEN))
                    return
                }
                val name = arguments[2]
                val screen = screenManager.getScreen(name)
                if (screen == null) {
                    player.sendMessage(Component.text("Screen '$name' not found.", NamedTextColor.RED))
                    return
                }
                screenManager.clearScreen(screen)
                player.sendMessage(Component.text("Screen '$name' cleared.", NamedTextColor.GREEN))
            }

            "install-ffmpeg" -> {
                player.sendMessage(Component.text("Checking / installing portable static FFmpeg...", NamedTextColor.YELLOW))
                scope.launch {
                    val resolved = FFmpegResolver.installStaticFFmpeg(plugin.dataFolder) { progressMsg ->
                        player.sendMessage(Component.text(progressMsg, NamedTextColor.GRAY))
                    }
                    if (resolved != null) {
                        player.sendMessage(Component.text("Static portable FFmpeg installed and verified at: $resolved", NamedTextColor.GREEN))
                    } else {
                        player.sendMessage(Component.text("Failed to automatically install FFmpeg. You can manually place an ffmpeg binary into plugins/vc-addon-discs/bin/ffmpeg", NamedTextColor.RED))
                    }
                }
            }

            else -> sendHelp(player)
        }
    }

    private fun sendHelp(player: Player) {
        player.sendMessage(Component.text("=== Screen Commands ===", NamedTextColor.GOLD))
        player.sendMessage(Component.text("/disc screen create [name]", NamedTextColor.AQUA).append(Component.text(" - Select 2 corner item frames + jukebox", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen list", NamedTextColor.AQUA).append(Component.text(" - List all registered screens", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen link <name>", NamedTextColor.AQUA).append(Component.text(" - Link screen to targeted jukebox", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen unlink <name>", NamedTextColor.AQUA).append(Component.text(" - Unlink screen from jukebox", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen play <name> <url>", NamedTextColor.AQUA).append(Component.text(" - Manually play video on screen", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen stop [name]", NamedTextColor.AQUA).append(Component.text(" - Stop screen playback", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen clear [name]", NamedTextColor.AQUA).append(Component.text(" - Clear screen to black", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen delete <name>", NamedTextColor.AQUA).append(Component.text(" - Delete a screen", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen cancel", NamedTextColor.AQUA).append(Component.text(" - Cancel active selection", NamedTextColor.GRAY)))
        player.sendMessage(Component.text("/disc screen install-ffmpeg", NamedTextColor.AQUA).append(Component.text(" - Download portable static FFmpeg", NamedTextColor.GRAY)))
    }
}

