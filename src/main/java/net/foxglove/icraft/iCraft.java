package net.foxglove.icraft;

import net.foxglove.icraft.block.ModBlockEntities;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.item.ModAttachments;
import net.foxglove.icraft.item.ModCreativeModeTabs;
import net.foxglove.icraft.item.ModDataComponents;
import net.foxglove.icraft.item.ModItems;
import net.foxglove.icraft.recipe.ModRecipes;
import net.minecraft.world.item.CreativeModeTabs;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

// Must match the mod id declared in META-INF/neoforge.mods.toml
@Mod(iCraft.MOD_ID)
public class iCraft {
    public static final String MOD_ID = "icraft_music";

    // Runs first when the mod is loaded; FML injects the mod event bus and the ModContainer
    // (used for config registration) automatically.
    public iCraft(IEventBus modEventBus, ModContainer modContainer) {
        // Wire up the deferred registries; the actual registration happens when RegisterEvent fires.
        ModCreativeModeTabs.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModAttachments.register(modEventBus);
        ModRecipes.register(modEventBus);

        // Add the iPod and speaker to the vanilla creative tabs (the custom iCraft tab is built in ModCreativeModeTabs)
        modEventBus.addListener(this::addCreative);

        // Let FML create and load the client-side config file (config/icraft_music-client.toml) from our spec
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);

    }

    // The iPod goes in "Tools & Utilities" and the speaker in "Functional Blocks", next to the jukebox.
    // The other iPod colours are only in the iCraft tab (see ModCreativeModeTabs).
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.IPOD);
        }

        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBlocks.SPEAKER);
            event.accept(ModBlocks.DOCK);
        }
    }
}
