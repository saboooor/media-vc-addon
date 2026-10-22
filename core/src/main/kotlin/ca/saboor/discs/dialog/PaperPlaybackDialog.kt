package ca.saboor.discs.dialog

import ca.saboor.discs.PlaybackDialogView
import java.util.function.Consumer
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Paper's dialog factory, loaded via reflection only on dialog-capable servers (Paper 1.21.3+).
 */
object PaperPlaybackDialog {
    private const val ROOT = "io.papermc.paper.registry.data.dialog."

    fun isAvailable(): Boolean = try {
        Class.forName("io.papermc.paper.dialog.Dialog")
        true
    } catch (_: Throwable) {
        false
    }

    private fun invoke(type: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val method = type.methods.first { method ->
            method.name == name && method.parameterCount == args.size &&
                method.parameterTypes.zip(args).all { (parameter, argument) ->
                    argument == null || parameter.isInstance(argument) ||
                        parameter == Int::class.javaPrimitiveType && argument is Int ||
                        parameter == Boolean::class.javaPrimitiveType && argument is Boolean
                }
        }
        method.trySetAccessible()
        return method.invoke(target, *args)
    }

    private fun call(target: Any, name: String, vararg args: Any?) = invoke(target.javaClass, target, name, *args)!!
    private fun static(type: String, name: String, vararg args: Any?) = invoke(Class.forName(type), null, name, *args)!!

    fun icon(sprite: String, label: Component): Component {
        return try {
            val contents = static(
                "net.kyori.adventure.text.object.ObjectContents", "sprite",
                Key.key(if (sprite.startsWith("block/")) "minecraft:blocks" else PlaybackDialogView.spriteAtlas(Bukkit.getMinecraftVersion())),
                Key.key("minecraft:$sprite")
            )
            (static("net.kyori.adventure.text.Component", "object", contents) as Component)
                .append(Component.text(" ")).append(label)
        } catch (_: Throwable) {
            label
        }
    }

    fun buttonSprite(command: String?): String = when (command?.substringAfterLast(' ')) {
        "playchest" -> "item/chest_minecart"
        "loop" -> "item/clock_00"
        "shuffle" -> "item/ender_eye"
        "speakers", "stereo" -> "block/jukebox_side"
        "note_link", "note_left", "note_right" -> "block/note_block"
        "note_unlink", "note_left_unlink", "note_right_unlink" -> "item/shears"
        "holo_edit", "holo_menu" -> "item/armor_stand"
        "holo_reset" -> "item/compass_00"
        null -> "item/oak_door"
        "play", "pause" -> "item/music_disc_cat"
        "seek", "refresh" -> "item/clock_00"
        "back" -> "item/ender_pearl"
        "forward", "skip" -> "item/spectral_arrow"
        "queue_menu" -> "item/book"
        "volume_menu" -> "item/goat_horn"
        "quieter" -> "item/echo_shard"
        "louder" -> "item/goat_horn"
        "viz_menu", "viz_toggle", "viz_wider", "viz_narrower", "viz_taller", "viz_shorter", "viz_reset" -> "item/amethyst_shard"
        "audio_menu", "crossfade", "fadeless", "fademore", "fadetoggle" -> "item/repeater"
        "effects_menu", "fx_eq_menu" -> "item/comparator"
        "fx_clear" -> "item/barrier"
        "fx_toggle_8d" -> "item/compass_00"
        "fx_toggle_echo" -> "item/echo_shard"
        "fx_toggle_underwater" -> "item/prismarine_shard"
        "fx_toggle_tremolo", "fx_toggle_vibrato" -> "item/amethyst_shard"
        "fx_toggle_nightcore", "fx_toggle_vaporwave" -> "item/clock_00"
        "stop" -> "item/redstone"
        "eject" -> "item/iron_pickaxe"
        "lamp_menu", "lamp_link", "lamp_link_bass", "lamp_link_mid", "lamp_link_highs", "lamp_unlink", "lamp_unset_all", "lamp_list", "lamp_show" -> "item/glowstone_dust"
        "screen_menu", "screen_link" -> "item/item_frame"
        "screen_subtitles" -> "item/writable_book"
        "screen_unlink" -> "item/shears"
        "screen_clear" -> "item/water_bucket"
        "prev" -> "item/ender_pearl"
        "next" -> "item/spectral_arrow"
        "search" -> "item/compass_00"
        "clear_search" -> "item/barrier"
        "inv" -> "item/chest"
        "disconnect" -> "item/barrier"
        else -> when {
            command?.contains("search") == true -> "item/compass_00"
            command?.startsWith("/disc burn") == true -> "item/music_disc_cat"
            command?.contains("burn") == true -> "item/music_disc_cat"
            command?.substringAfterLast(' ')?.startsWith("volume_minus_") == true -> "item/echo_shard"
            command?.substringAfterLast(' ')?.startsWith("volume_") == true -> "item/goat_horn"
            command?.substringAfterLast(' ')?.startsWith("viz_") == true -> "item/amethyst_shard"
            command?.substringAfterLast(' ')?.startsWith("seek_minus_") == true -> "item/ender_pearl"
            command?.substringAfterLast(' ')?.startsWith("seek_plus_") == true -> "item/spectral_arrow"
            command?.substringAfterLast(' ')?.startsWith("lamp_falloff_") == true -> "item/clock_00"
            else -> "item/paper"
        }
    }

    data class DialogTextInput(
        val key: String,
        val label: String,
        val initial: String = "",
        val maxLength: Int = 200,
        val width: Int = 300
    )

    fun show(
        player: Player,
        body: List<Component>,
        buttons: List<Pair<String, String>>,
        queueLabels: Map<String, Component> = emptyMap(),
        title: String,
        columns: Int = 2,
        buttonWidth: Int = 150,
        inputs: List<DialogTextInput> = emptyList()
    ) {
        val dialogClass = Class.forName("io.papermc.paper.dialog.Dialog")
        fun button(label: String, command: String?) = static(
            ROOT + "ActionButton", "create",
            queueLabels[command] ?: icon(buttonSprite(command), Component.text(label)),
            Component.text(label),
            buttonWidth,
            command?.let { cmd ->
                if (cmd.contains("$(")) {
                    static(ROOT + "action.DialogAction", "commandTemplate", cmd)
                } else {
                    static(ROOT + "action.DialogAction", "staticAction", ClickEvent.runCommand(cmd))
                }
            }
        )
        val baseBuilder = static(ROOT + "DialogBase", "builder", Component.text(title))
        call(baseBuilder, "pause", false)
        call(baseBuilder, "canCloseWithEscape", true)
        call(baseBuilder, "body", body.map { static(ROOT + "body.DialogBody", "plainMessage", it, 350) })
        if (inputs.isNotEmpty()) {
            val builtInputs = inputs.map { inp ->
                val builder = static(ROOT + "input.DialogInput", "text", inp.key, Component.text(inp.label))
                if (inp.initial.isNotEmpty()) call(builder, "initial", inp.initial)
                call(builder, "maxLength", inp.maxLength)
                call(builder, "width", inp.width)
                call(builder, "labelVisible", true)
                call(builder, "build")
            }
            call(baseBuilder, "inputs", builtInputs)
        }
        val base = call(baseBuilder, "build")
        val type = static(
            ROOT + "type.DialogType", "multiAction",
            buttons.map { (label, command) -> button(label, command) },
            button("Close", null),
            columns
        )
        val dialog = invoke(dialogClass, null, "create", Consumer<Any> { factory ->
            val builder = call(factory, "empty")
            call(builder, "base", base)
            call(builder, "type", type)
        })!!
        invoke(player.javaClass, player, "showDialog", dialog)
    }
}

