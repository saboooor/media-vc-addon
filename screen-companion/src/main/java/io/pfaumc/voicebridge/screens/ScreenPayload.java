package io.pfaumc.voicebridge.screens;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ScreenPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<ScreenPayload> FRAME = type("screen_rgb");
    public static Type<ScreenPayload> type(String name) { return new Type<>(Identifier.fromNamespaceAndPath("vcb", name)); }
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPayload> CODEC = new StreamCodec<>() {
        @Override public ScreenPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 32764) throw new IllegalArgumentException("Oversized screen payload");
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new ScreenPayload(bytes);
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ScreenPayload payload) { buf.writeBytes(payload.bytes); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return FRAME; }
}
