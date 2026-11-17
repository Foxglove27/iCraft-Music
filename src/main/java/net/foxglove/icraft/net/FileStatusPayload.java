package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The server's answer about a file. HAVE: the server has it. SEND: go ahead and upload. REFUSED: no, see
 * {@code reason} (a translation key suffix). NOT_YET: asked for a file the server does not have yet, ask again later.
 */
public record FileStatusPayload(String hash, int status, String reason) implements CustomPacketPayload {
    public static final int HAVE = 0;
    public static final int SEND = 1;
    public static final int REFUSED = 2;
    public static final int NOT_YET = 3;

    public static final Type<FileStatusPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "file_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FileStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), FileStatusPayload::hash,
            ByteBufCodecs.VAR_INT, FileStatusPayload::status,
            ByteBufCodecs.stringUtf8(32), FileStatusPayload::reason,
            FileStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
