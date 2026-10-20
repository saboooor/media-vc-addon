package su.plo.voice.discs.visualizer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisualizerLayoutTest {
    @Test fun `position and dimensions adjust independently`() {
        val layout = VisualizerLayout().adjust(dx = 0.25f, dy = -0.25f, dz = 0.5f, dw = 0.25f, dh = -0.25f)
        assertEquals(VisualizerLayout(0.25f, -0.25f, 0.5f, 1.25f, 0.75f), layout)
        assertEquals(VisualizerLayout(), VisualizerLayout())
    }

    @Test fun `movement and size have bounded limits`() {
        val layout = VisualizerLayout().adjust(dx = 100f, dy = -100f, dz = 100f, dw = 100f, dh = -100f)
        assertEquals(VisualizerLayout(16f, -16f, 16f, 4f, 0.25f), layout)
    }
}
