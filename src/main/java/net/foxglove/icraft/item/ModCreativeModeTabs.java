package net.foxglove.icraft.item;

import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.iCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TAB =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, iCraft.MOD_ID);

    public static final Supplier<CreativeModeTab> ICRAFT_TAB = CREATIVE_MODE_TAB.register("icraft_tab",
            () -> CreativeModeTab.builder().icon(() -> new ItemStack(ModItems.IPOD.get()))
                    .title(Component.translatable("creativetab.icraft.ipod"))
                    .displayItems((itemDisplayParameters, output) -> {
                        for (DeferredItem<IpodItem> ipod : ModItems.IPODS.values()) {
                            output.accept(ipod);
                        }
                        output.accept(ModBlocks.SPEAKER);
                        output.accept(ModBlocks.DOCK);
                    }).build());

    public static void register(IEventBus eventbus){
        CREATIVE_MODE_TAB.register(eventbus);
    }


}
