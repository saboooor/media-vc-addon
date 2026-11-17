package ca.saboor.discs.screen.companion;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ScreenMetadataPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<ScreenMetadataPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("vcb", "screen_metadata"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenMetadataPayload> CODEC = new StreamCodec<>() {
        @Override public ScreenMetadataPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() < 16 || buf.readableBytes() > 16 + 32 * 32 * 4)
                throw new IllegalArgumentException("Invalid screen metadata");
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new ScreenMetadataPayload(bytes);
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ScreenMetadataPayload payload) {
            buf.writeBytes(payload.bytes);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
