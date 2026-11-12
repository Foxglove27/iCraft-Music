package net.foxglove.icraft.item;

import net.foxglove.icraft.iCraft;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(iCraft.MOD_ID);

    public static final DeferredItem<Item> IPOD = ITEMS.register("ipod",
            () -> new IpodItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}