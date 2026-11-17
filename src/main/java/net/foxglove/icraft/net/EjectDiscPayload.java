package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent when a player Ctrl + right-clicks with an iPod in hand: take its last disc out. Only the client knows that
 * Ctrl was held, so the server is told. The server checks that the hand really holds an iPod.
 */
public record EjectDiscPayload(boolean offHand) implements CustomPacketPayload {
    public static final Type<EjectDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "eject_disc"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EjectDiscPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, EjectDiscPayload::offHand,
            EjectDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
