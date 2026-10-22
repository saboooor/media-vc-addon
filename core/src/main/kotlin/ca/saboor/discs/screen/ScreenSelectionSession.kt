package ca.saboor.discs.screen

import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.Block
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player

class ScreenSelectionSession(
    val playerUuid: UUID,
    val screenName: String,
    val preLinkedJukebox: Block? = null,
    private val onComplete: (ScreenSelectionSession) -> Unit
) {
    enum class State {
        WAITING_FOR_CORNER_1,
        WAITING_FOR_CORNER_2,
        WAITING_FOR_JUKEBOX
    }

    var state: State = State.WAITING_FOR_CORNER_1
        private set

    var corner1: ItemFrame? = null
        private set

    var corner2: ItemFrame? = null
        private set

    var detectionResult: ScreenGridDetector.DetectionResult? = null
        private set

    var linkedJukebox: Block? = null
        private set

    var expiresAt: Long = System.currentTimeMillis() + 180_000L
    private var lastClickTime: Long = 0L

    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt

    fun handleItemFrameClick(frame: ItemFrame, player: Player): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 350L) {
            return true // Debounce rapid double triggers
        }
        lastClickTime = now
        expiresAt = now + 180_000L

        when (state) {
            State.WAITING_FOR_CORNER_1 -> {
                corner1 = frame
                state = State.WAITING_FOR_CORNER_2
                val loc = frame.location.block
                player.sendMessage(
                    Component.text("First corner selected at (${loc.x}, ${loc.y}, ${loc.z}) facing ${frame.facing}.", NamedTextColor.YELLOW)
                )
                player.sendMessage(
                    Component.text("Now right-click the opposite corner, or click this frame again for a 1x1 screen.", NamedTextColor.GOLD)
                )
                return true
            }

            State.WAITING_FOR_CORNER_2 -> {
                val c1 = corner1 ?: return false
                corner2 = frame

                try {
                    val result = ScreenGridDetector.detectGrid(c1, frame)
                    detectionResult = result

                    if (preLinkedJukebox != null) {
                        linkedJukebox = preLinkedJukebox
                        player.sendMessage(
                            Component.text(
                                "Screen grid detected: ${result.columns}x${result.rows} frames (${result.columns * 128}x${result.rows * 128} pixels).",
                                NamedTextColor.GREEN
                            )
                        )
                        onComplete(this)
                        return true
                    }

                    state = State.WAITING_FOR_JUKEBOX

                    player.sendMessage(
                        Component.text(
                            "Screen grid detected: ${result.columns}x${result.rows} frames (${result.columns * 128}x${result.rows * 128} pixels).",
                            NamedTextColor.GREEN
                        )
                    )
                    player.sendMessage(
                        Component.text(
                            "Now right-click a jukebox to link this screen, or type '/disc screen finish' to finish without a jukebox.",
                            NamedTextColor.YELLOW
                        )
                    )
                    return true
                } catch (e: ScreenGridDetector.DetectionException) {
                    player.sendMessage(
                        Component.text("Failed to detect grid: ${e.message}", NamedTextColor.RED)
                    )
                    player.sendMessage(
                        Component.text("Please right-click a valid opposite corner item frame.", NamedTextColor.GRAY)
                    )
                    return true
                }
            }

            State.WAITING_FOR_JUKEBOX -> {
                // If they click another item frame while waiting for jukebox, notify them
                player.sendMessage(
                    Component.text("Screen frame region already selected. Please right-click a jukebox to link it.", NamedTextColor.YELLOW)
                )
                return true
            }
        }
    }

    fun handleJukeboxClick(block: Block, player: Player): Boolean {
        if (state != State.WAITING_FOR_JUKEBOX) return false

        linkedJukebox = block
        onComplete(this)
        return true
    }

    fun finishWithoutJukebox(): Boolean {
        if (state != State.WAITING_FOR_JUKEBOX) return false
        onComplete(this)
        return true
    }
}

