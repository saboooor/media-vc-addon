package ca.saboor.discs.visualizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TheatreLightFadeTest {
    @Test fun `playback fades to zero over three seconds then stop restores brightness`() {
        val fade = TheatreLightFade(15, 0)
        assertEquals(15, fade.update(true, 15, 0))
        assertEquals(10, fade.update(true, 15, 1_000_000_000))
        assertEquals(5, fade.update(true, 15, 2_000_000_000))
        assertEquals(0, fade.update(true, 15, 3_000_000_000))
        assertEquals(0, fade.update(false, 15, 4_000_000_000))
        assertEquals(5, fade.update(false, 15, 5_000_000_000))
        assertEquals(15, fade.update(false, 15, 7_000_000_000))
    }
    @Test fun `pausing mid fade reverses smoothly and honors original brightness`() {
        val fade = TheatreLightFade(10, 0)
        fade.update(true, 10, 0)
        assertEquals(5, fade.update(true, 10, 1_000_000_000))
        assertEquals(5, fade.update(false, 10, 1_000_000_000))
        assertEquals(10, fade.update(false, 10, 2_000_000_000))
        assertEquals(10, fade.update(false, 10, 20_000_000_000))
    }
    @Test fun `idle time before playback does not skip the fade`() {
        val fade = TheatreLightFade(15, 0)
        assertEquals(15, fade.update(true, 15, 30_000_000_000))
        assertEquals(10, fade.update(true, 15, 31_000_000_000))
    }
}
