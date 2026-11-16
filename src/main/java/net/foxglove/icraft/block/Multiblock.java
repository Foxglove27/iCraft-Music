package net.foxglove.icraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * A big speaker built from ordinary speaker blocks: 2 wide x 4 tall, all facing the same way. There is
 * no special block; the shape is detected when needed. It is louder and carries further than one speaker.
 */
public final class Multiblock {
    public static final int WIDTH = 2;
    public static final int HEIGHT = 4;

    /**
     * @param anchor the corner with the lowest x, y and z; identifies the multiblock
     * @param center the middle of the whole rectangle (where the sound comes from)
     * @param top    the middle of its top face (where the music notes rise from)
     */
    public record Result(BlockPos anchor, Vec3 center, Vec3 top, Direction.Axis axis) {
        /** True if {@code pos} is one of the 8 blocks of this rectangle. */
        public boolean contains(BlockPos pos) {
            int dx = pos.getX() - anchor.getX();
            int dz = pos.getZ() - anchor.getZ();
            int dy = pos.getY() - anchor.getY();
            int widthX = axis == Direction.Axis.X ? WIDTH : 1;
            int widthZ = axis == Direction.Axis.Z ? WIDTH : 1;
            return dx >= 0 && dx < widthX && dz >= 0 && dz < widthZ && dy >= 0 && dy < HEIGHT;
        }
    }

    private Multiblock() {}

    /** Finds a complete 2x4 rectangle of speakers that contains {@code pos}, or null. */
    @Nullable
    public static Result find(BlockGetter level, BlockPos pos) {
        return find(level::getBlockState, pos);
    }

    /**
     * Like {@link #find}, but reads {@code pos} as {@code removed} (a speaker that was just taken away), to
     * tell whether that block was part of a complete wall.
     */
    @Nullable
    public static Result findAssuming(BlockGetter level, BlockPos pos, BlockState removed) {
        return find(p -> p.equals(pos) ? removed : level.getBlockState(p), pos);
    }

    @Nullable
    private static Result find(Function<BlockPos, BlockState> level, BlockPos pos) {
        BlockState state = level.apply(pos);
        if (!state.is(ModBlocks.SPEAKER.get())) return null;
        Direction facing = state.getValue(SpeakerBlock.FACING);
        Direction across = facing.getClockWise(); // the horizontal direction along the wall the speakers face from

        // the clicked block can be any of the 8 blocks of the rectangle
        for (int dx = 0; dx < WIDTH; dx++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                BlockPos start = pos.relative(across, -dx).below(dy);
                Result r = rectangleAt(level, start, across, facing);
                if (r != null) return r;
            }
        }
        return null;
    }

    @Nullable
    private static Result rectangleAt(Function<BlockPos, BlockState> level, BlockPos start, Direction across, Direction facing) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double sumX = 0, sumY = 0, sumZ = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                BlockPos p = start.relative(across, x).above(y);
                BlockState s = level.apply(p);
                if (!s.is(ModBlocks.SPEAKER.get()) || s.getValue(SpeakerBlock.FACING) != facing) return null;
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxY = Math.max(maxY, p.getY());
                sumX += p.getX() + 0.5;
                sumY += p.getY() + 0.5;
                sumZ += p.getZ() + 0.5;
            }
        }
        int n = WIDTH * HEIGHT;
        Vec3 center = new Vec3(sumX / n, sumY / n, sumZ / n);
        Vec3 top = new Vec3(center.x, maxY + 1.0, center.z);
        return new Result(new BlockPos(minX, minY, minZ), center, top, across.getAxis());
    }
}
