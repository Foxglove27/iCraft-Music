package net.foxglove.icraft.client;

import net.foxglove.icraft.ClientConfig;
import net.foxglove.icraft.block.DockBlock;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.block.Multiblock;
import net.foxglove.icraft.client.audio.ChannelMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Where the sound of a dock comes from: its speakers, worked out from what is standing in the world. */
public final class DockOutputs {
    /** See {@link DockBlockEntity#SPEAKER_VOLUME}; loudness itself is capped at full volume by Minecraft. */
    public static final float SPEAKER_VOLUME = DockBlockEntity.SPEAKER_VOLUME;

    /** A speaker counts as left or right of the dock when it is more than this many blocks to the side. */
    private static final double SIDE_THRESHOLD = 1.0;

    /**
     * One place the sound comes from: a single speaker or a multiblock. 'top' is where the music notes rise.
     * 'channel' is the part of a stereo track it plays (left, right or both mixed).
     */
    public record Output(BlockPos anchor, boolean multiblock, Vec3 pos, Vec3 top, float volume, ChannelMode channel) {
        /** How far (in blocks) this speaker can be heard. */
        public double range() {
            return Math.max(volume, 1.0F) * 16.0;
        }

        Output withChannel(ChannelMode newChannel) {
            return new Output(anchor, multiblock, pos, top, volume, newChannel);
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
                    outputs.add(new Output(anchor, true, big.center(), big.top(), SPEAKER_VOLUME * 2, ChannelMode.MIX));
                }
            } else if (level.getBlockState(anchor).is(ModBlocks.SPEAKER.get())) {
                Vec3 center = Vec3.atCenterOf(anchor);
                outputs.add(new Output(anchor, false, center, center.add(0, 0.5, 0), SPEAKER_VOLUME, ChannelMode.MIX));
            }
        }
        return assignChannels(dock, outputs);
    }

    /**
     * Gives every speaker its side of the music. Picture a listener standing in front of the dock, looking at it:
     * a speaker more than {@link #SIDE_THRESHOLD} blocks to their left plays the left channel, one to their right
     * the right channel, one near the middle line both mixed. Stereo is only used when there is at least one
     * speaker on each side; otherwise (one speaker, or all on the same side) every speaker plays the mix, so no
     * part of the music goes missing.
     */
    private static List<Output> assignChannels(DockBlockEntity dock, List<Output> outputs) {
        if (outputs.size() < 2 || !stereoEnabled()) return outputs;
        BlockState state = dock.getBlockState();
        if (!state.hasProperty(DockBlock.FACING)) return outputs;
        // the dock's front faces the listener, so the listener's right hand points to the dock's left
        Direction right = state.getValue(DockBlock.FACING).getCounterClockWise();
        Vec3 dockCenter = Vec3.atCenterOf(dock.getBlockPos());

        List<ChannelMode> sides = new ArrayList<>();
        boolean anyLeft = false, anyRight = false;
        for (Output o : outputs) {
            Vec3 offset = o.pos().subtract(dockCenter);
            double side = offset.x * right.getStepX() + offset.z * right.getStepZ();
            ChannelMode mode = side > SIDE_THRESHOLD ? ChannelMode.RIGHT
                    : side < -SIDE_THRESHOLD ? ChannelMode.LEFT : ChannelMode.MIX;
            anyLeft |= mode == ChannelMode.LEFT;
            anyRight |= mode == ChannelMode.RIGHT;
            sides.add(mode);
        }
        if (!anyLeft || !anyRight) return outputs; // all MIX already

        List<Output> result = new ArrayList<>(outputs.size());
        for (int i = 0; i < outputs.size(); i++) result.add(outputs.get(i).withChannel(sides.get(i)));
        return result;
    }

    private static boolean stereoEnabled() {
        try {
            return ClientConfig.STEREO_SPEAKERS.get();
        } catch (IllegalStateException e) {
            return true; // the config is not loaded yet: the default
        }
    }
}
