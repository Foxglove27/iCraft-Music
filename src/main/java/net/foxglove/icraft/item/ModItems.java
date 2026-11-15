package net.foxglove.icraft.item;

import net.foxglove.icraft.iCraft;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(iCraft.MOD_ID);

    // One iPod item per colour, registered in a loop
    public static final Map<IpodColor, DeferredItem<IpodItem>> IPODS = new EnumMap<>(IpodColor.class);

    static {
        for (IpodColor color : IpodColor.values()) {
            IPODS.put(color, ITEMS.register(color.itemName(),
                    () -> new IpodItem(new Item.Properties().stacksTo(1), color)));
        }
    }

    // The plain white iPod, used for the creative tab icon and the now-playing icon
    public static final DeferredItem<IpodItem> IPOD = IPODS.get(IpodColor.WHITE);

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}