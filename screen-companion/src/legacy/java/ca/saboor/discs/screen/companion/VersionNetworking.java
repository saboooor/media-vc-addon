package ca.saboor.discs.screen.companion;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

final class VersionNetworking {
    static void register() {
        PayloadTypeRegistry.playS2C().register(ScreenPayload.FRAME, ScreenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClearPayload.TYPE, ClearPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HelloPayload.TYPE, HelloPayload.CODEC);
    }
}
