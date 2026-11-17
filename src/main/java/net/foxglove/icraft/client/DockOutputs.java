package net.foxglove.icraft.client;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.block.Multiblock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Where the sound of a dock comes from: its speakers, worked out from what is standing in the world. */
public final class DockOutputs {
    /**
     * Range of a single speaker = max(volume, 1) x 16 blocks, so 2.0 = 32 blocks. A multiblock uses twice
     * this value (64 blocks). Loudness itself is capped at full volume by Minecraft.
     */
    public static final float SPEAKER_VOLUME = 2.0F;

    /** One place the sound comes from: a single speaker or a multiblock. 'top' is where the music notes rise. */
    public record Output(BlockPos anchor, boolean multiblock, Vec3 pos, Vec3 top, float volume) {
        /** How far (in blocks) this speaker can be heard. */
        public double range() {
            return Math.max(volume, 1.0F) * 16.0;
        }
    }

    /**
     * True if a listener at {@code listener} is within the speaker's range. A sound the engine would skip for being
     * too far away never asks for its stream, so it must not be waited for when speakers start together.
     */
    public static boolean inRange(Output output, Vec3 listener) {
        double range = output.range();
        return listener.distanceToSqr(output.pos()) <= range * range;
    }

    private DockOutputs() {}

    /**
     * The usable speakers of a dock. A speaker in a chunk that is not loaded cannot be checked: it keeps its
     * entry from {@code previous}. A broken multiblock is skipped (never turned into a single speaker); the
     * dock's server ticker removes it from the list a moment later.
     */
    public static List<Output> of(Level level, DockBlockEntity dock, List<Output> previous) {
        List<Output> outputs = new ArrayList<>();
        for (DockBlockEntity.Slot slot : dock.getSpeakers()) {
            BlockPos anchor = slot.anchor();
            if (!level.hasChunkAt(anchor)) {
                previous.stream().filter(o -> o.anchor().equals(anchor)).findFirst().ifPresent(outputs::add);
                continue;
            }
            if (slot.multiblock()) {
                Multiblock.Result big = Multiblock.find(level, anchor);
                if (big != null && big.anchor().equals(anchor)) {
                    outputs.add(new Output(anchor, true, big.center(), big.top(), SPEAKER_VOLUME * 2));
                }
            } else if (level.getBlockState(anchor).is(ModBlocks.SPEAKER.get())) {
                Vec3 center = Vec3.atCenterOf(anchor);
                outputs.add(new Output(anchor, false, center, center.add(0, 0.5, 0), SPEAKER_VOLUME));
            }
        }
        return outputs;
    }
}
