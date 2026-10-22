package ca.saboor.discs.screen.companion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoDimensionsTest {
    @Test void acceptsHdAndMapSizesWhileBoundingDecodedMemory() {
        assertTrue(VideoDimensions.valid(1920, 1080));
        assertTrue(VideoDimensions.valid(3840, 2160));
        assertTrue(VideoDimensions.valid(128, 128));
        assertFalse(VideoDimensions.valid(8192, 8192));
        assertFalse(VideoDimensions.valid(8193, 1));
        assertFalse(VideoDimensions.valid(0, 1080));
        assertFalse(VideoDimensions.valid(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
