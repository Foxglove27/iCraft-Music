package net.foxglove.icraft.block;

import com.mojang.serialization.MapCodec;
import net.foxglove.icraft.client.ClientHooks;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The docking station. Right-click it with an iPod to dock it, with an empty hand to open the docked
 * iPod, sneak + empty hand to take it out. (Sneak + right-click with an iPod links that iPod to the
 * dock, see {@link IpodItem#useOn}.) The docked iPod plays through the speakers added to the dock.
 */
public class DockBlock extends BaseEntityBlock {
    public static final MapCodec<DockBlock> CODEC = simpleCodec(DockBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 10, 15);

    public DockBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // A structure block (rotation / mirror on load) turns the block with the structure
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // facing the player
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // a block entity block is invisible unless this says otherwise
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DockBlockEntity(pos, state);
    }

    // The dock keeps its speaker list true (server side only)
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return createTickerHelper(type, ModBlockEntities.DOCK.get(), ClientHooks::dockClientTick);
        }
        return createTickerHelper(type, ModBlockEntities.DOCK.get(), DockBlockEntity::serverTick);
    }

    // An iPod in hand: dock it (sneaking never gets here; that is handled by IpodItem.useOn)
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof IpodItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (level.isClientSide() && !dock.hasIpod()) {
            // music the player is playing from this iPod carries on through the dock instead of getting stuck
            ClientHooks.ipodDocked(GlobalPos.of(level.dimension(), pos), stack);
        }
        if (!level.isClientSide()) {
            if (dock.hasIpod()) {
                player.displayClientMessage(Component.translatable("message.icraft_music.dock.full"), true);
            } else {
                ItemStack docked = stack.split(1);
                if (!docked.has(ModDataComponents.IPOD_ID.get())) { // a fresh iPod has no ID before it was in an inventory
                    docked.set(ModDataComponents.IPOD_ID.get(), UUID.randomUUID());
                }
                dock.setIpod(docked, player);
                player.displayClientMessage(Component.translatable("message.icraft_music.dock.docked"), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    // Empty hand: open the docked iPod; sneaking: take it out
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock)) return InteractionResult.PASS;

        if (!dock.hasIpod()) {
            if (!level.isClientSide()) {
                player.displayClientMessage(Component.translatable("message.icraft_music.dock.empty"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        dock.claimIfUnowned(player); // a dock filled by an older version belongs to whoever uses it first

        if (player.isShiftKeyDown()) {
            if (level.isClientSide() && (dock.isOwner(player) || player.hasPermissions(2))) {
                ClientHooks.ipodTakenFromDock(dock); // what the dock plays carries on for this player
            }
            if (!level.isClientSide()) {
                if (dock.isOwner(player) || player.hasPermissions(2)) { // the owner, or an operator / creative
                    player.getInventory().placeItemBackInInventory(dock.takeIpod());
                    player.displayClientMessage(Component.translatable("message.icraft_music.dock.taken"), true);
                } else {
                    player.displayClientMessage(Component.translatable("message.icraft_music.dock.not_yours", dock.getOwnerName()), true);
                }
            }
        } else if (level.isClientSide()) {
            ClientHooks.openDockScreen(dock);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    // Breaking the dock gives the iPod back
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof DockBlockEntity dock && dock.hasIpod()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dock.getIpod());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
