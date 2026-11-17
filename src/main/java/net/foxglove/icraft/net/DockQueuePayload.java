package net.foxglove.icraft.net;

import net.foxglove.icraft.iCraft;
import net.foxglove.icraft.net.share.QueueEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The owner of a dock starts a list of their own files. The server checks everything again (see
 * {@code DockBlockEntity.queue}). {@code startOffsetTicks} is where in the first item to begin (a saved position).
 */
public record DockQueuePayload(BlockPos pos, int startIndex, int startOffsetTicks, boolean shuffle, int repeat,
                               List<QueueEntry> entries) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 100;

    public static final Type<DockQueuePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "dock_queue"));

    private static final StreamCodec<RegistryFriendlyByteBuf, QueueEntry> ENTRY_CODEC = StreamCodec.of(
            (buf, e) -> {
                buf.writeUtf(e.hash(), 64);
                buf.writeUtf(e.ext(), 8);
                buf.writeUtf(e.title(), QueueEntry.MAX_TEXT * 4);
                buf.writeUtf(e.album(), QueueEntry.MAX_TEXT * 4);
                buf.writeVarInt(e.startTicks());
                buf.writeVarInt(e.endTicks() + 1); // -1 (end of file) becomes 0, so it stays small
                buf.writeVarInt(e.lengthTicks());
            },
            buf -> new QueueEntry(buf.readUtf(64), buf.readUtf(8), buf.readUtf(QueueEntry.MAX_TEXT * 4),
                    buf.readUtf(QueueEntry.MAX_TEXT * 4), buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt()));

    public static final StreamCodec<RegistryFriendlyByteBuf, DockQueuePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DockQueuePayload::pos,
            ByteBufCodecs.VAR_INT, DockQueuePayload::startIndex,
            ByteBufCodecs.VAR_INT, DockQueuePayload::startOffsetTicks,
            ByteBufCodecs.BOOL, DockQueuePayload::shuffle,
            ByteBufCodecs.VAR_INT, DockQueuePayload::repeat,
            ENTRY_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), DockQueuePayload::entries,
            DockQueuePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
