package net.foxglove.icraft.item;

import net.foxglove.icraft.client.ClientHooks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public class IpodItem extends Item {
    public static final int MAX_DISCS = 8;

    public IpodItem(Properties properties) {
        super(properties);
    }

    public static List<ItemStack> getDiscs(ItemStack ipod) {
        List<ItemStack> discs = new ArrayList<>();
        ipod.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .nonEmptyItems().forEach(s -> discs.add(s.copy()));
        return discs;
    }

    private static void setDiscs(ItemStack ipod, List<ItemStack> discs) {
        ipod.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(discs));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            List<ItemStack> discs = getDiscs(stack);
            if (!discs.isEmpty()) {
                ItemStack removed = discs.remove(discs.size() - 1);
                if (level.isClientSide()) {
                    ClientHooks.discRemoved(removed);
                } else {
                    player.getInventory().placeItemBackInInventory(removed);
                    setDiscs(stack, discs);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (level.isClientSide()) {
            ClientHooks.openIpodScreen(stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
    // Called when the cursor stack (other) is right-clicked onto this iPod in an inventory slot
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack ipod, ItemStack other, Slot slot,
                                            ClickAction action, Player player, SlotAccess access) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) {
            return false;
        }

        List<ItemStack> discs = getDiscs(ipod);

        if (other.isEmpty()) {
            if (discs.isEmpty()) return false;
            ItemStack removed = discs.remove(discs.size() - 1);
            access.set(removed);
            setDiscs(ipod, discs);
            if (player.level().isClientSide()) {
                ClientHooks.discRemoved(removed);
            }
            return true;
        }

        if (other.has(DataComponents.JUKEBOX_PLAYABLE) && discs.size() < MAX_DISCS) {
            discs.add(other.split(1));
            setDiscs(ipod, discs);
            return true;
        }

        return false;
    }
}