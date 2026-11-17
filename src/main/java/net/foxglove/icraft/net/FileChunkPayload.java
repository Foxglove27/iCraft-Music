package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A piece of an uploaded file (client to server). Pieces must come in order. Stays below the 32 KiB packet limit. */
public record FileChunkPayload(String hash, long offset, byte[] data) implements CustomPacketPayload {
    public static final int MAX_CHUNK = 30_000;
    public static final Type<FileChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "file_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FileChunkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), FileChunkPayload::hash,
            ByteBufCodecs.VAR_LONG, FileChunkPayload::offset,
            ByteBufCodecs.byteArray(MAX_CHUNK), FileChunkPayload::data,
            FileChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
