package ca.saboor.discs.screen.companion;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreensClient implements ClientModInitializer {
    private record Encoded(long session, long revision, byte[] jpeg, int columns, int rows, int[] maps, FrameAssembler.Frame video) {}
    private record Decoded(long session, long revision, int[] pixels, int width, int height, int columns, int rows, int[] maps) {}
    public record Texture(Identifier id, DynamicTexture image, int columns, int rows, int[] maps) {}
    private static final FrameAssembler assembler = new FrameAssembler();
    private static final Map<Integer, Integer> anchors = new HashMap<>();
    private static final int LIMIT = 8;
    private static final Map<Integer, Texture> textures = new LinkedHashMap<>(16, .75f, true);
    private static final Map<Integer, Long> revisions = new HashMap<>();
    private static final ConcurrentMap<Integer, Encoded> pending = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, H264VideoDecoder> videos = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, Encoded> videoSessions = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, Decoded> decoded = new ConcurrentHashMap<>();
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vcb-screen-decoder"); t.setDaemon(true); return t;
    });
    private static final AtomicBoolean decoding = new AtomicBoolean();
    private static long session, revision;
    private static int ticks;
    private static net.minecraft.client.multiplayer.ClientLevel level;

    @Override public void onInitializeClient() {
        VersionNetworking.register();
        ClientPlayNetworking.registerGlobalReceiver(ScreenPayload.FRAME, (payload, context) -> accept(payload.bytes()));
        ClientPlayNetworking.registerGlobalReceiver(ClearPayload.TYPE, (payload, context) -> {
            int id = payload.mapId();
            int anchor = anchors.getOrDefault(id, id);
            revisions.remove(anchor); pending.remove(anchor); videos.remove(anchor); videoSessions.remove(anchor); decoded.remove(anchor); assembler.clear(anchor); release(anchor);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != level) { reset(); level = client.level; }
            // Bukkit advertises its plugin channels after the play connection is established.
            if (++ticks % 100 == 0 && ClientPlayNetworking.canSend(HelloPayload.TYPE)) ClientPlayNetworking.send(new HelloPayload());
        });
    }

    /** Called on the render thread before drawing, independently of the 20 Hz game tick. */
    public static void uploadReadyFrames() {
        var client = Minecraft.getInstance();
        if (client.level != level) { reset(); level = client.level; }
        int uploaded = 0;
        for (int id : decoded.keySet()) {
            Decoded frame = decoded.remove(id);
            if (frame != null && frame.session == session && Objects.equals(revisions.get(id), frame.revision)) upload(id, frame);
            if (++uploaded == LIMIT) break;
        }
    }

    private static void accept(byte[] bytes) {
        FrameAssembler.Frame frame = assembler.accept(bytes);
        if (frame == null) return;
        int id = frame.anchor();
        if (!revisions.containsKey(id) && revisions.size() >= LIMIT) return;
        long next = revisions.computeIfAbsent(id, key -> ++revision);
        var encoded = new Encoded(session, next, frame.jpeg(), frame.columns(), frame.rows(), frame.maps(), frame);
        if (frame.codec() == 1) {
            pending.remove(id);
            videoSessions.put(id, encoded);
            videos.computeIfAbsent(id, key -> new H264VideoDecoder()).offer(frame);
        } else {
            videos.remove(id); videoSessions.remove(id);
            pending.put(id, encoded);
        }
        scheduleDecode();
    }

    private static void scheduleDecode() {
        if (!decoding.compareAndSet(false, true)) return;
        decoder.execute(() -> {
            try {
                while (hasPending()) {
                for (int id : videos.keySet()) {
                    var video = videos.get(id);
                    var owner = videoSessions.get(id);
                    if (video == null || owner == null) continue;
                    var encoded = video.poll();
                    if (encoded == null) continue;
                    var image = video.decode(encoded);
                    if (image != null && videos.get(id) == video) decoded.put(id, new Decoded(owner.session, owner.revision,
                        image.pixels(), image.width(), image.height(), encoded.columns(), encoded.rows(), encoded.maps()));
                }
                for (int id : pending.keySet()) {
                    Encoded frame = pending.remove(id);
                    if (frame == null) continue;
                    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(frame.jpeg))) {
                        var readers = ImageIO.getImageReaders(input);
                        if (!readers.hasNext()) continue;
                        var reader = readers.next();
                        try {
                            reader.setInput(input);
                            if (!VideoDimensions.valid(reader.getWidth(0), reader.getHeight(0))) continue;
                            var image = reader.read(0);
                            decoded.put(id, new Decoded(frame.session, frame.revision, image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()), image.getWidth(), image.getHeight(), frame.columns, frame.rows, frame.maps));
                        } finally { reader.dispose(); }
                    } catch (Exception ignored) { /* Keep palette or last valid RGB texture. */ }
                }
                }
            } finally {
                decoding.set(false);
                if (hasPending()) scheduleDecode();
            }
        });
    }

    private static boolean hasPending() { return !pending.isEmpty() || videos.values().stream().anyMatch(H264VideoDecoder::hasFrames); }

    private static void upload(int id, Decoded frame) {
        int width = frame.width, height = frame.height;
        if (width > 8192 || height > 8192) return;
        Texture texture = textures.get(id);
        if (texture != null && (texture.columns != frame.columns || texture.rows != frame.rows
                || texture.image.getPixels() == null || texture.image.getPixels().getWidth() != width
                || texture.image.getPixels().getHeight() != height || !Arrays.equals(texture.maps, frame.maps))) { release(id); texture = null; }
        if (texture == null) {
            if (textures.size() >= LIMIT) release(textures.keySet().iterator().next());
            Identifier name = Identifier.fromNamespaceAndPath("vcb_screens", "screen/" + id);
            var image = new NativeImage(width, height, false);
            var dynamic = new DynamicTexture(() -> "VCB video screen " + id, image);
            Minecraft.getInstance().getTextureManager().register(name, dynamic);
            texture = new Texture(name, dynamic, frame.columns, frame.rows, frame.maps);
            textures.put(id, texture);
            for (int map : frame.maps) anchors.put(map, id);
        }
        NativeImage image = texture.image.getPixels();
        if (image == null) return;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setPixel(x, y, frame.pixels[y * width + x] | 0xff000000);
        texture.image.upload();
    }

    public static Texture surface(int mapId) {
        Integer anchor = anchors.get(mapId);
        return anchor == null ? null : textures.get(anchor);
    }
    public static boolean isAnchor(int mapId) { return Objects.equals(anchors.get(mapId), mapId); }
    private static void release(int id) {
        Texture old = textures.remove(id);
        if (old != null) {
            for (int map : old.maps) anchors.remove(map, id);
            Minecraft.getInstance().getTextureManager().release(old.id);
        }
    }
    private static void reset() {
        session++; videos.clear(); videoSessions.clear(); pending.clear(); decoded.clear(); revisions.clear(); assembler.reset();
        for (int id : new ArrayList<>(textures.keySet())) release(id);
        ticks = 0; level = null;
    }
}
