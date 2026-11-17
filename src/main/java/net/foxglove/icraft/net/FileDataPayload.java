package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A piece of a shared file (server to client). {@code total} is the size of the whole file. */
public record FileDataPayload(String hash, long total, long offset, byte[] data) implements CustomPacketPayload {
    public static final int MAX_CHUNK = 32_768;
    public static final Type<FileDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "file_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FileDataPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), FileDataPayload::hash,
            ByteBufCodecs.VAR_LONG, FileDataPayload::total,
            ByteBufCodecs.VAR_LONG, FileDataPayload::offset,
            ByteBufCodecs.byteArray(MAX_CHUNK), FileDataPayload::data,
            FileDataPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
