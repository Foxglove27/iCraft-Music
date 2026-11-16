package net.foxglove.icraft.block;

import net.foxglove.icraft.iCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, iCraft.MOD_ID);

    public static final Supplier<BlockEntityType<DockBlockEntity>> DOCK = BLOCK_ENTITIES.register("dock",
            () -> BlockEntityType.Builder.of(DockBlockEntity::new, ModBlocks.DOCK.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
