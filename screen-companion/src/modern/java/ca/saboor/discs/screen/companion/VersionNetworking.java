package ca.saboor.discs.screen.companion;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

final class VersionNetworking {
    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ScreenPayload.FRAME, ScreenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ClearPayload.TYPE, ClearPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
    }
}
