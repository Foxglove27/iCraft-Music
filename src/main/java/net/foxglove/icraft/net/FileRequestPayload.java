package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A listener asks the server for a shared file, starting at {@code offset} (to resume a download). */
public record FileRequestPayload(String hash, long offset) implements CustomPacketPayload {
    public static final Type<FileRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "file_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FileRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), FileRequestPayload::hash,
            ByteBufCodecs.VAR_LONG, FileRequestPayload::offset,
            FileRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
