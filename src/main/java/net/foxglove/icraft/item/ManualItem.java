package net.foxglove.icraft.item;

import net.foxglove.icraft.client.ClientHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The iCraft Manual: right-click opens a screen that explains the mod (ManualScreen, content in ManualContent). */
public class ManualItem extends Item {
    public ManualItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Only the client has a screen; common code reaches client code through ClientHooks only
        if (level.isClientSide()) ClientHooks.openManual();
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
