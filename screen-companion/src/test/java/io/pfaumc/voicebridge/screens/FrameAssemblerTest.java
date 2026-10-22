package io.pfaumc.voicebridge.screens;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class FrameAssemblerTest {
    private static byte[] body(int columns, int rows, byte[] jpeg) {
        ByteBuffer body = ByteBuffer.allocate(8 + columns * rows * 4 + jpeg.length);
        body.putInt(columns).putInt(rows);
        for (int i = 0; i < columns * rows; i++) body.putInt(10 + i);
        return body.put(jpeg).array();
    }
    private static byte[] chunk(int sequence, byte[] body, int offset, int length) {
        return ByteBuffer.allocate(16 + length).putInt(10).putInt(sequence).putInt(body.length)
                .putInt(offset).put(body, offset, length).array();
    }

    @Test void reassemblesOneWholeScreenWithoutExposingPartialFrames() {
        var assembler = new FrameAssembler();
        byte[] jpeg = new byte[90000];
        Arrays.fill(jpeg, (byte) 123);
        byte[] body = body(3, 2, jpeg);
        for (int offset = 0; offset < body.length; offset += 32748) {
            int size = Math.min(32748, body.length - offset);
            byte[] packet = chunk(1, body, offset, size);
            assertTrue(packet.length <= 32764);
            var frame = assembler.accept(packet);
            if (offset + size < body.length) assertNull(frame);
            else {
                assertNotNull(frame);
                assertEquals(3, frame.columns());
                assertEquals(2, frame.rows());
                assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15}, frame.maps());
                assertArrayEquals(jpeg, frame.jpeg());
            }
        }
    }

    @Test void rejectsMissingMixedAndClearedFragments() {
        var assembler = new FrameAssembler();
        byte[] body = body(2, 2, new byte[40000]);
        assertNull(assembler.accept(chunk(1, body, 20000, body.length - 20000)));
        assertNull(assembler.accept(chunk(1, body, 0, 20000)));
        assertNull(assembler.accept(chunk(2, body, 20000, body.length - 20000)));
        assembler.clear(10);
        assertNull(assembler.accept(chunk(1, body, 20000, body.length - 20000)));
        assertNull(assembler.accept(chunk(2, body, 0, 20000)));
        assertNotNull(assembler.accept(chunk(2, body, 20000, body.length - 20000)));
    }

    @Test void rejectsOversizedBodiesInvalidLayoutsAndDuplicateMaps() {
        var assembler = new FrameAssembler();
        byte[] huge = ByteBuffer.allocate(17).putInt(10).putInt(1).putInt(Integer.MAX_VALUE).putInt(0).put((byte) 0).array();
        assertNull(assembler.accept(huge));
        byte[] duplicate = body(2, 1, new byte[]{1});
        ByteBuffer.wrap(duplicate).putInt(12, 10);
        assertNull(assembler.accept(chunk(1, duplicate, 0, duplicate.length)));
        byte[] invalid = body(1, 1, new byte[]{1});
        ByteBuffer.wrap(invalid).putInt(0, Integer.MAX_VALUE);
        assertNull(assembler.accept(chunk(2, invalid, 0, invalid.length)));
    }

    @Test void resetInvalidatesPartialFrame() {
        var assembler = new FrameAssembler();
        byte[] body = body(1, 1, new byte[100]);
        assembler.accept(chunk(1, body, 0, 50));
        assembler.reset();
        assertNull(assembler.accept(chunk(1, body, 50, body.length - 50)));
    }
}
