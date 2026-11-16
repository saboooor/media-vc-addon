package ca.saboor.discs.screen.companion;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

/** Bounded encoded dependency queue; recover from gaps/overflow at the next IDR. */
final class H264VideoDecoder {
    record Image(int width, int height, int[] pixels) {}
    private final ArrayDeque<FrameAssembler.Frame> queue = new ArrayDeque<>();
    private int queuedBytes, receivedStream, receivedNumber = -1;
    private boolean waiting = true;
    private H264Decoder decoder;
    private Picture buffer, rgb;
    private int bufferWidth, bufferHeight;
    private final int[][] pixelBuffers = new int[2][];
    private int pixelBufferIndex;
    private int stream, number = -1;
    synchronized void offer(FrameAssembler.Frame frame) {
        boolean gap = receivedNumber >= 0 && (receivedStream != frame.stream() || frame.number() != receivedNumber + 1);
        if (gap || queue.size() >= 4 || queuedBytes + frame.jpeg().length > 8 * 1024 * 1024) {
            queue.clear(); queuedBytes = 0; waiting = true;
        }
        receivedStream = frame.stream(); receivedNumber = frame.number();
        if (waiting && !frame.keyframe()) return;
        if (frame.keyframe()) waiting = false;
        queue.add(frame); queuedBytes += frame.jpeg().length;
    }
    synchronized FrameAssembler.Frame poll() { var frame = queue.poll(); if (frame != null) queuedBytes -= frame.jpeg().length; return frame; }
    synchronized boolean hasFrames() { return !queue.isEmpty(); }
    private static H264Decoder createDecoder() {
        var decoder = new H264Decoder();
        // JCodec 0.2.5 has no close API for its private slice pool. Our encoder emits one
        // slice per frame, so disable that pool before decoding and release it immediately.
        try {
            var threaded = H264Decoder.class.getDeclaredField("threaded"); threaded.setAccessible(true); threaded.setBoolean(decoder, false);
            var pool = H264Decoder.class.getDeclaredField("tp"); pool.setAccessible(true);
            var executor = (java.util.concurrent.ExecutorService) pool.get(decoder);
            if (executor != null) executor.shutdownNow();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported JCodec build", failure); }
        return decoder;
    }
    Image decode(FrameAssembler.Frame encoded) {
        try {
            var data = ByteBuffer.wrap(encoded.jpeg());
            var nals = H264Utils.splitFrame(data.duplicate());
            for (var nal : nals) {
                int type = nal.get(nal.position()) & 31;
                if (!encoded.keyframe() && (type == 7 || type == 8 || type == 5)) throw new IllegalArgumentException("Unexpected parameter change");
            }
            if (encoded.keyframe()) {
                if (!VideoDimensions.valid(encoded.width(), encoded.height()))
                    throw new IllegalArgumentException("Dimensions mismatch");
                int expectedAlignedW = (encoded.width() + 15) & ~15;
                int expectedAlignedH = (encoded.height() + 15) & ~15;
                boolean found = false;
                for (var nal : nals) {
                    if ((nal.get(nal.position()) & 31) != 7) continue;
                    var spsData = nal.duplicate(); spsData.get();
                    var sps = H264Utils.readSPS(spsData);
                    long w = ((long) sps.picWidthInMbsMinus1 + 1) * 16;
                    long h = ((long) sps.picHeightInMapUnitsMinus1 + 1) * 16;
                    if (sps.profileIdc != 66 || sps.numRefFrames > 2 || sps.numRefFrames < 0 || !sps.frameMbsOnlyFlag || w > 8192 || h > 8192 || w * h > 8_388_608 || w < 1 || h < 1)
                        throw new IllegalArgumentException("Unsupported H.264 dimensions/profile");
                    if (w != expectedAlignedW || h != expectedAlignedH)
                        throw new IllegalArgumentException("Dimensions mismatch");
                    found = true;
                }
                if (!found) throw new IllegalArgumentException("Missing SPS");
                if (decoder == null || stream != encoded.stream()) decoder = createDecoder();
                if (buffer == null || rgb == null || bufferWidth != expectedAlignedW || bufferHeight != expectedAlignedH) {
                    buffer = Picture.create(expectedAlignedW, expectedAlignedH, ColorSpace.YUV420);
                    rgb = Picture.create(expectedAlignedW, expectedAlignedH, ColorSpace.RGB);
                    bufferWidth = expectedAlignedW;
                    bufferHeight = expectedAlignedH;
                }
                stream = encoded.stream(); number = encoded.number() - 1;
            }
            if (decoder == null || stream != encoded.stream() || encoded.number() != number + 1) { decoder = null; return null; }
            var picture = decoder.decodeFrame(data, buffer.getData());
            if (picture == null) throw new IllegalArgumentException("Missing picture");
            int width = picture.getCroppedWidth(), height = picture.getCroppedHeight();
            if (width != encoded.width() || height != encoded.height())
                throw new IllegalArgumentException("Dimensions mismatch");
            number = encoded.number();
            Transform transform = ColorUtil.getTransform(picture.getColor(), ColorSpace.RGB);
            transform.transform(picture, rgb);
            int left = picture.getCrop() == null ? 0 : picture.getCrop().getX(), top = picture.getCrop() == null ? 0 : picture.getCrop().getY();
            byte[] bytes = rgb.getPlaneData(0);
            int pixelCount = width * height;
            int slot = pixelBufferIndex;
            pixelBufferIndex = (slot + 1) & 1;
            int[] pixels = pixelBuffers[slot];
            if (pixels == null || pixels.length != pixelCount) {
                pixels = new int[pixelCount];
                pixelBuffers[slot] = pixels;
            }
            int stride = rgb.getWidth() * 3;
            int srcRow = (top * rgb.getWidth() + left) * 3;
            int dstRow = 0;
            for (int y = 0; y < height; y++) {
                int src = srcRow;
                int end = dstRow + width;
                for (int dst = dstRow; dst < end; dst++) {
                    pixels[dst] = 0xff000000
                        | ((bytes[src] + 128) << 16)
                        | ((bytes[src + 1] + 128) << 8)
                        | (bytes[src + 2] + 128);
                    src += 3;
                }
                srcRow += stride;
                dstRow = end;
            }
            return new Image(width, height, pixels);
        } catch (RuntimeException failure) { decoder = null; return null; }
    }
}
