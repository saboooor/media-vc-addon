package ca.saboor.discs.screen.companion;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H264VideoDecoderTest {
    private List<FrameAssembler.Frame> frames() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/baseline-64x48.h264")) { bytes = input.readAllBytes(); }
        var starts = new ArrayList<Integer>();
        for (int i = 0; i + 4 < bytes.length; i++)
            if (bytes[i] == 0 && bytes[i+1] == 0 && bytes[i+2] == 0 && bytes[i+3] == 1 && (bytes[i+4] & 31) == 9) starts.add(i);
        starts.add(bytes.length);
        var result = new ArrayList<FrameAssembler.Frame>();
        for (int n = 0; n + 1 < starts.size(); n++) {
            byte[] unit = Arrays.copyOfRange(bytes, starts.get(n), starts.get(n+1));
            result.add(new FrameAssembler.Frame(10, 1, 1, new int[]{10}, unit, 1, 64, 48, 1, n, n % 10 == 0));
        }
        return result;
    }
    @Test void decodesBaselineKeyframesAndDependentFrames() throws Exception {
        var decoder = new H264VideoDecoder();
        byte[] reference;
        try (var input = getClass().getResourceAsStream("/baseline-first.rgb")) { reference = input.readAllBytes(); }
        int count = 0;
        for (var frame : frames()) {
            var image = decoder.decode(frame);
            assertNotNull(image, "frame " + frame.number());
            assertEquals(64, image.width()); assertEquals(48, image.height());
            assertEquals(64 * 48, image.pixels().length);
            if (count == 0) {
                long error = 0;
                for (int i = 0; i < image.pixels().length; i++) for (int c = 0; c < 3; c++)
                    error += Math.abs(((image.pixels()[i] >> (16 - c * 8)) & 255) - (reference[i * 3 + c] & 255));
                assertTrue(error / (double) reference.length < 8.0, "RGB output differs from FFmpeg: " + error / (double) reference.length);
            }
            count++;
        }
        assertEquals(20, count);
    }
    @Test void gapAndLateJoinWaitForNextKeyframe() throws Exception {
        var frames = frames(); var decoder = new H264VideoDecoder();
        assertNull(decoder.decode(frames.get(1)));
        assertNotNull(decoder.decode(frames.get(0)));
        assertNull(decoder.decode(frames.get(2)));
        assertNull(decoder.decode(frames.get(3)));
        assertNotNull(decoder.decode(frames.get(10)));
        assertNotNull(decoder.decode(frames.get(11)));
    }
    @Test void queueOverflowDropsDependenciesUntilAKeyframe() throws Exception {
        var frames = frames(); var decoder = new H264VideoDecoder();
        for (int n = 0; n < 8; n++) decoder.offer(frames.get(n));
        assertNull(decoder.poll());
        decoder.offer(frames.get(10)); decoder.offer(frames.get(11));
        assertEquals(10, decoder.poll().number()); assertEquals(11, decoder.poll().number());
        assertNull(decoder.poll());
    }
}
