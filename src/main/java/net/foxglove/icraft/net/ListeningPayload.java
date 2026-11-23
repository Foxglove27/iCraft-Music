package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent by a player's client when music starts or stops playing on them (B18): {@code color} is the ordinal of the
 * playing iPod's {@code IpodColor}, or -1 for "not listening". The server keeps it in an attachment that is
 * synced to the players who see them, so they can draw headphones.
 */
public record ListeningPayload(int color) implements CustomPacketPayload {
    public static final Type<ListeningPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "listening"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ListeningPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, ListeningPayload::color, ListeningPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
