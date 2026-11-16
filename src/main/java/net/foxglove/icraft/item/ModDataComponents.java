package net.foxglove.icraft.item;

import net.foxglove.icraft.iCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;
import java.util.function.Supplier;

public class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, iCraft.MOD_ID);

    public static final Supplier<DataComponentType<BlockPos>> LINKED_SPEAKER =
            COMPONENTS.register("linked_speaker", () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    // The discs loaded into an iPod (our own component, so other mods don't mistake it for a container)
    public static final Supplier<DataComponentType<IpodDiscs>> DISCS =
            COMPONENTS.register("discs", () -> DataComponentType.<IpodDiscs>builder()
                    .persistent(IpodDiscs.CODEC)
                    .networkSynchronized(IpodDiscs.STREAM_CODEC)
                    .build());

    // A unique ID for every iPod, so a playing song can belong to one specific iPod
    public static final Supplier<DataComponentType<UUID>> IPOD_ID =
            COMPONENTS.register("ipod_id", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    public static void register(IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }
}