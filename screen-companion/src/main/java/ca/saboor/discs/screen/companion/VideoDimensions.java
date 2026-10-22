package ca.saboor.discs.screen.companion;

final class VideoDimensions {
    static boolean valid(int width, int height) {
        return width > 0 && height > 0 && width <= 8192 && height <= 8192
                && (long) width * height <= 8_294_400;
    }
}
