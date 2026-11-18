package net.foxglove.icraft.item;

import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.iCraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Gives every player one manual the first time they join a world (switch: ServerConfig.GIVE_MANUAL). */
@EventBusSubscriber(modid = iCraft.MOD_ID)
public class ManualGift {
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ServerConfig.GIVE_MANUAL.get()) return;
        if (player.getData(ModAttachments.MANUAL_GIVEN.get())) return; // got it before

        player.setData(ModAttachments.MANUAL_GIVEN.get(), true);
        ItemStack manual = new ItemStack(ModItems.MANUAL.get());
        // inventory full: drop it at the player's feet instead of losing it
        if (!player.getInventory().add(manual)) player.drop(manual, false);
    }
}
