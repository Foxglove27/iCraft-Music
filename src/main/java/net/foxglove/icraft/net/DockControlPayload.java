package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent by the player who owns a dock to change what it plays. The server checks everything (see
 * {@code DockBlockEntity.control}). {@code arg} is the disc index for PLAY, ticks for SEEK, 1 or 0 for EXTERNAL.
 */
public record DockControlPayload(BlockPos pos, int action, int arg, boolean shuffle, int repeat)
        implements CustomPacketPayload {
    public static final int PLAY = 0;
    public static final int PAUSE = 1;
    public static final int RESUME = 2;
    public static final int NEXT = 3;
    public static final int PREVIOUS = 4;
    public static final int SEEK = 5;
    public static final int STOP = 6;
    public static final int MODES = 7;
    public static final int EXTERNAL = 8;

    public static final Type<DockControlPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "dock_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DockControlPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DockControlPayload::pos,
            ByteBufCodecs.VAR_INT, DockControlPayload::action,
            ByteBufCodecs.VAR_INT, DockControlPayload::arg,
            ByteBufCodecs.BOOL, DockControlPayload::shuffle,
            ByteBufCodecs.VAR_INT, DockControlPayload::repeat,
            DockControlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
