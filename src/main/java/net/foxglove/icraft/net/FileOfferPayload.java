package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A player offers to upload a file to the server (shared file cache). {@code hash} is the SHA-256 of the content. */
public record FileOfferPayload(String hash, long size, String ext) implements CustomPacketPayload {
    public static final Type<FileOfferPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "file_offer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FileOfferPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), FileOfferPayload::hash,
            ByteBufCodecs.VAR_LONG, FileOfferPayload::size,
            ByteBufCodecs.stringUtf8(8), FileOfferPayload::ext,
            FileOfferPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
