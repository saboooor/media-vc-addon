package ca.saboor.discs.screen.companion;

import java.nio.ByteBuffer;
import java.util.*;

/** Reassembles bounded, ordered transport fragments into a single complete JPEG. */
final class FrameAssembler {
    record Frame(int anchor, int columns, int rows, int[] maps, byte[] jpeg, int codec, int width, int height, int stream, int number, boolean keyframe) {}
    private record Assembly(int sequence, byte[] bytes, int received) {}
    private final Map<Integer, Assembly> assemblies = new HashMap<>();
    private static final int MAX_BYTES = 4 * 1024 * 1024 + 1060;

    void clear(int anchor) { assemblies.remove(anchor); }
    void reset() { assemblies.clear(); }

    Frame accept(byte[] bytes) {
        if (bytes.length <= 16 || bytes.length > 32764) return null;
        ByteBuffer input = ByteBuffer.wrap(bytes);
        int id = input.getInt(), number = input.getInt(), total = input.getInt(), offset = input.getInt();
        int length = input.remaining();
        if (id < 0 || total < 41 || total > MAX_BYTES || offset < 0 || offset > total - length) return null;
        if (offset == 0) {
            if (!assemblies.containsKey(id) && assemblies.size() >= 8) return null;
            assemblies.put(id, new Assembly(number, new byte[total], 0));
        }
        Assembly current = assemblies.get(id);
        if (current == null || current.sequence != number || current.bytes.length != total || current.received != offset) return null;
        input.get(current.bytes, offset, length);
        int received = offset + length;
        if (received != total) {
            assemblies.put(id, new Assembly(number, current.bytes, received));
            return null;
        }
        assemblies.remove(id);
        ByteBuffer body = ByteBuffer.wrap(current.bytes);
        int columns = body.getInt(), rows = body.getInt();
        if (columns < 1 || rows < 1 || columns > 64 || rows > 64 || columns * rows > 256) return null;
        if (body.remaining() < 28) return null;
        int codec = body.getInt(), width = body.getInt(), height = body.getInt(), stream = body.getInt(), frameNumber = body.getInt(), key = body.getInt(), reserved = body.getInt();
        if (codec < 0 || codec > 1 || frameNumber < 0 || key < 0 || key > 1 || reserved != 0) return null;
        if (codec == 1 && !VideoDimensions.valid(width, height)) return null;
        int count = columns * rows;
        if (body.remaining() <= count * 4) return null;
        int[] maps = new int[count];
        Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < count; i++) {
            maps[i] = body.getInt();
            if (maps[i] < 0 || !unique.add(maps[i])) return null;
        }
        if (maps[0] != id) return null;
        byte[] jpeg = new byte[body.remaining()];
        body.get(jpeg);
        return new Frame(id, columns, rows, maps, jpeg, codec, width, height, stream, frameNumber, key == 1);
    }
}
