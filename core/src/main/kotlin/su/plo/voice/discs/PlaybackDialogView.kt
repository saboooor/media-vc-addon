package su.plo.voice.discs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Formatting kept independent of Paper's version-specific dialog and object-component APIs. */
internal object PlaybackDialogView {
    fun spriteAtlas(version: String): String {
        val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 1 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return if (major > 1 || minor > 21 || minor == 21 && patch >= 11) "minecraft:items" else "minecraft:blocks"
    }

    fun filledSegments(position: Long, duration: Long, width: Int = 24): Int =
        if (duration <= 0) 0 else (position.toDouble() / duration * width).toInt().coerceIn(0, width)

    private fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun progress(position: Long, duration: Long, live: Boolean): Component {
        if (live) return Component.text("● LIVE  ·  ${time(position)} elapsed", NamedTextColor.RED)
        if (duration <= 0) return Component.text("Loading audio…", NamedTextColor.GRAY)
        val filled = filledSegments(position, duration)
        val percent = (position.toDouble() / duration * 100).toInt().coerceIn(0, 100)
        return Component.text("━".repeat(filled), NamedTextColor.GREEN)
            .append(Component.text("━".repeat(24 - filled), NamedTextColor.DARK_GRAY))
            .append(Component.text("  $percent%\n${time(position)} / ${time(duration)}", NamedTextColor.GRAY))
    }
}
