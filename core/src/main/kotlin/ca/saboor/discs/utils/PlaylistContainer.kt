package ca.saboor.discs.utils

import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.ShulkerBox

/** Containers supported as disc queues above a jukebox, including every shulker color. */
fun BlockState?.playlistContainer(): Container? = when (this) {
    is Chest, is ShulkerBox -> this as Container
    else -> null
}
