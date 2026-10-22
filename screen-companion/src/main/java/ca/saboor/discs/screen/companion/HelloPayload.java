package ca.saboor.discs.screen.companion;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HelloPayload() implements CustomPacketPayload {
    public static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vcb", "screen_hello"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = new StreamCodec<>() {
        @Override public HelloPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() != 1 || buf.readByte() != 2) throw new IllegalArgumentException("Screen protocol mismatch");
            return new HelloPayload();
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, HelloPayload payload) { buf.writeByte(2); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
