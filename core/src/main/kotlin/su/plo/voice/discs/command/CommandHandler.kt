package su.plo.voice.discs.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.command.McCommandSource
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.discs.utils.PluginKoinComponent
import su.plo.voice.discs.utils.extend.*
import java.util.concurrent.ConcurrentHashMap

class CommandHandler: CommandExecutor, TabCompleter, PluginKoinComponent {

    private val voiceServer: PlasmoVoiceServer? by getterOrNull<PlasmoVoiceServer>()

    private val subCommands: MutableMap<String, SubCommand> = ConcurrentHashMap()

    fun <T : SubCommand> addSubCommand(subCommand: () -> T): CommandHandler {
        subCommand()
            .also { subCommands[it.name] = it }
            .also { registerPermissions(it.permissions) }
        return this
    }

    private fun registerPermissions(permissions: List<Pair<String, PermissionDefault>>) {
        permissions.forEach { (perm, default) ->
            try {
                voiceServer?.minecraftServer?.permissionManager?.registerBukkit(
                    "pv.addon.discs.$perm",
                    default
                )
            } catch (_: Throwable) {}

            val bukkitDefault = when (default) {
                PermissionDefault.TRUE -> org.bukkit.permissions.PermissionDefault.TRUE
                PermissionDefault.FALSE -> org.bukkit.permissions.PermissionDefault.FALSE
                PermissionDefault.NOT_OP -> org.bukkit.permissions.PermissionDefault.NOT_OP
                else -> org.bukkit.permissions.PermissionDefault.OP
            }

            listOf("pv.addon.discs.$perm", "vc.addon.discs.$perm").forEach { permName ->
                if (Bukkit.getPluginManager().getPermission(permName) == null) {
                    try {
                        Bukkit.getPluginManager().addPermission(
                            org.bukkit.permissions.Permission(permName, bukkitDefault)
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private val unknownCommandComponent: McTextComponent
        get() = McTextComponent.translatable(
            "pv.addon.discs.error.unknown_subcommand",
            subCommands.keys.joinToString(", ")
        )

    override fun onCommand(sender: CommandSender, command: Command, label: String, arguments: Array<out String>): Boolean {
        val player = sender.asPlayer()
        val voicePlayer = voiceServer?.let { player?.asVoicePlayer(it) }

        val subCommand = arguments.getOrNull(0) ?: run {
            if (voicePlayer != null) {
                voicePlayer.instance.sendMessage(unknownCommandComponent)
            } else {
                sender.sendMessage(Component.text("Unknown subcommand. Available: ${subCommands.keys.joinToString(", ")}", NamedTextColor.RED))
            }
            return false
        }

        subCommands[subCommand]?.let {
            it.execute(sender, arguments)
            return true
        }

        if (voicePlayer != null) {
            voicePlayer.instance.sendMessage(unknownCommandComponent)
        } else {
            sender.sendMessage(Component.text("Unknown subcommand. Available: ${subCommands.keys.joinToString(", ")}", NamedTextColor.RED))
        }

        return false
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        arguments: Array<out String>,
    ): List<String> {

        if (arguments.isEmpty()) return subCommands
            .filter { it.value.checkCanExecute(sender) }
            .keys
            .toList()

        val subCommand = arguments.getOrNull(0) ?: return listOf()

        if (arguments.size == 1) return subCommands
            .filter { it.key.startsWith(subCommand) && it.value.checkCanExecute(sender) }
            .keys
            .toList()

        subCommands[subCommand]?.let { return it.suggest(sender, arguments) }

        return listOf()
    }

    fun getTranslationStringByKey(key: String, source: McCommandSource): String {
        return voiceServer?.languages?.getServerLanguage(source)?.get(key) ?: key
    }
}
