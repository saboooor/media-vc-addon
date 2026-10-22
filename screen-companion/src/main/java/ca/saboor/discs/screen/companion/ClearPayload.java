package ca.saboor.discs.screen.companion;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ClearPayload(int mapId) implements CustomPacketPayload {
    public static final Type<ClearPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vcb", "screen_clear"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClearPayload> CODEC = new StreamCodec<>() {
        @Override public ClearPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() != 4) throw new IllegalArgumentException("Invalid screen clear");
            return new ClearPayload(buf.readInt());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ClearPayload payload) { buf.writeInt(payload.mapId); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
