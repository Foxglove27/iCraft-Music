package net.foxglove.icraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

public class SpeakerBlock extends HorizontalDirectionalBlock {
    // (FACING comes from HorizontalDirectionalBlock)
    private static final int ROW_TICKS = 3;                          // delay between two rows of the formed animation
    private static final int[] CHIME_SEMITONES = {0, 4, 7, 12};      // a rising major arpeggio, one note per row
    public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // When the last block of a 2x4 wall is placed, the wall "forms": every block of it gets a scheduled tick
    // that is later by one row, so it lights up from the bottom to the top.
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide() || oldState.is(this)) return;
        Multiblock.Result big = Multiblock.find(level, pos);
        if (big == null) return;
        // A speaker added to a wall that was already complete forms another 2x4 rectangle with some of its blocks:
        // that is no new wall, so no chime
        for (int x = 0; x < Multiblock.WIDTH; x++) {
            for (int y = 0; y < Multiblock.HEIGHT; y++) {
                BlockPos p = big.anchor().above(y);
                p = big.axis() == Direction.Axis.X ? p.east(x) : p.south(x);
                if (!p.equals(pos) && Multiblock.findWithout(level, p, pos) != null) return;
            }
        }

        for (int x = 0; x < Multiblock.WIDTH; x++) {
            for (int y = 0; y < Multiblock.HEIGHT; y++) {
                BlockPos p = big.anchor().above(y);
                p = big.axis() == Direction.Axis.X ? p.east(x) : p.south(x);
                level.scheduleTick(p, this, y * ROW_TICKS + 1);
            }
        }
    }

    // One row of the formed animation: a chime that rises with the row, note and spark particles at the
    // front of the block, and a little burst over the top row
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Multiblock.Result big = Multiblock.find(level, pos);
        if (big == null) return; // broken again before the animation was done

        int row = pos.getY() - big.anchor().getY();
        Direction facing = state.getValue(FACING);
        double x = pos.getX() + 0.5 + facing.getStepX() * 0.55;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.55;

        level.sendParticles(ParticleTypes.NOTE, x, y + 0.3, z, 1, 0.2, 0.1, 0.2, 1.0);
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 6, 0.25, 0.25, 0.25, 0.02);

        float pitch = (float) Math.pow(2, CHIME_SEMITONES[Math.min(row, CHIME_SEMITONES.length - 1)] / 12.0);
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.9F, pitch);

        if (row == Multiblock.HEIGHT - 1) { // the top row finishes it
            level.sendParticles(ParticleTypes.FIREWORK, big.top().x, big.top().y + 0.2, big.top().z, 14, 0.4, 0.2, 0.4, 0.04);
        }
    }

    // A complete wall that loses a block "breaks": a low flat note and a puff of smoke from all 8 blocks.
    // (Explosions remove blocks one by one; only the first removal still sees a complete wall.)
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            Multiblock.Result big = Multiblock.findAssuming(level, pos, state);
            if (big != null && !wallRemains(level, big, pos)) {
                Direction facing = state.getValue(FACING);
                for (int x = 0; x < Multiblock.WIDTH; x++) {
                    for (int y = 0; y < Multiblock.HEIGHT; y++) {
                        BlockPos p = big.anchor().above(y);
                        p = big.axis() == Direction.Axis.X ? p.east(x) : p.south(x);
                        server.sendParticles(ParticleTypes.SMOKE, p.getX() + 0.5 + facing.getStepX() * 0.55, p.getY() + 0.5,
                                p.getZ() + 0.5 + facing.getStepZ() * 0.55, 5, 0.2, 0.2, 0.2, 0.02);
                        server.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                                2, 0.4, 0.4, 0.4, 0.1);
                    }
                }
                server.playSound(null, BlockPos.containing(big.center()), SoundEvents.NOTE_BLOCK_BASS.value(),
                        SoundSource.BLOCKS, 1.0F, 0.5F);
                server.playSound(null, BlockPos.containing(big.center()), SoundEvents.NOTE_BLOCK_CHIME.value(),
                        SoundSource.BLOCKS, 0.6F, 0.6F);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // True if the other blocks of this wall still form a complete wall without the removed block (a wall with
    // extra speakers on it): then nothing broke.
    private static boolean wallRemains(Level level, Multiblock.Result big, BlockPos removed) {
        for (int x = 0; x < Multiblock.WIDTH; x++) {
            for (int y = 0; y < Multiblock.HEIGHT; y++) {
                BlockPos p = big.anchor().above(y);
                p = big.axis() == Direction.Axis.X ? p.east(x) : p.south(x);
                if (!p.equals(removed) && Multiblock.findWithout(level, p, removed) != null) return true;
            }
        }
        return false;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // opposite of where the player looks = pointing back at the player
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
}