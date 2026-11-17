package net.foxglove.icraft.item;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.block.Multiblock;
import net.foxglove.icraft.client.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class IpodItem extends Item {
    public static final int MAX_DISCS = 32; // vanilla has 19 discs, so this leaves room for modded ones

    private final IpodColor color;

    public IpodItem(Properties properties, IpodColor color) {
        super(properties);
        this.color = color;
    }

    /** The colour of this iPod, which decides its GUI skin. */
    public IpodColor getColor() {
        return color;
    }

    public static List<ItemStack> getDiscs(ItemStack ipod) {
        List<ItemStack> discs = new ArrayList<>();
        for (ItemStack stored : ipod.getOrDefault(ModDataComponents.DISCS.get(), IpodDiscs.EMPTY).discs()) {
            discs.add(stored.copy());
        }
        return discs;
    }

    // True if this iPod holds a disc equal to the given one. Unlike getDiscs, it makes no copies,
    // so it is cheap enough to call every tick even with many discs loaded.
    public static boolean hasDisc(ItemStack ipod, ItemStack disc) {
        for (ItemStack stored : ipod.getOrDefault(ModDataComponents.DISCS.get(), IpodDiscs.EMPTY).discs()) {
            if (ItemStack.isSameItemSameComponents(stored, disc)) return true;
        }
        return false;
    }

    private static void setDiscs(ItemStack ipod, List<ItemStack> discs) {
        if (discs.isEmpty()) {
            ipod.remove(ModDataComponents.DISCS.get()); // an empty iPod stays identical to a fresh one
        } else {
            ipod.set(ModDataComponents.DISCS.get(), new IpodDiscs(discs));
        }
    }

    // Gives every iPod its own ID the first time the server sees it in an inventory.
    // The ID is a data component, so it survives dyeing, dropping and picking the iPod up again.
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!level.isClientSide() && !stack.has(ModDataComponents.IPOD_ID.get())) {
            stack.set(ModDataComponents.IPOD_ID.get(), UUID.randomUUID());
        }
        if (!level.isClientSide() && stack.has(ModDataComponents.LINKED_SPEAKER.get())) {
            stack.remove(ModDataComponents.LINKED_SPEAKER.get()); // the direct speaker link was replaced by docks
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide()) {
            // Ctrl + right-click takes the last disc out (only the client knows about Ctrl, so the server is told)
            if (!ClientHooks.ejectDiscWithCtrl(stack, hand)) ClientHooks.openIpodScreen(stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** Server side: takes the last disc out of {@code ipod} and gives it to the player. */
    public static void ejectLastDisc(Player player, ItemStack ipod) {
        List<ItemStack> discs = getDiscs(ipod);
        if (discs.isEmpty()) return;
        ItemStack removed = discs.remove(discs.size() - 1);
        player.getInventory().placeItemBackInInventory(removed);
        setDiscs(ipod, discs);
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

    // Using the iPod on a block: sneak + dock = link the iPod to that dock; a speaker = add it to / remove it
    // from the linked dock (the whole multiblock counts as one speaker). Everything else opens the GUI.
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack ipod = context.getItemInHand();

        if (level.getBlockState(pos).is(ModBlocks.DOCK.get()) && player != null && player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                ipod.set(ModDataComponents.LINKED_DOCK.get(), GlobalPos.of(level.dimension(), pos));
                player.displayClientMessage(Component.translatable("message.icraft.dock.linked"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        if (level.getBlockState(pos).is(ModBlocks.SPEAKER.get())) {
            if (!level.isClientSide() && player != null) {
                toggleSpeaker(level, pos, ipod, player);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS; // other blocks fall through to use() and open the GUI
    }

    private static void toggleSpeaker(Level level, BlockPos clicked, ItemStack ipod, Player player) {
        GlobalPos dockPos = ipod.get(ModDataComponents.LINKED_DOCK.get());
        if (dockPos == null) {
            player.displayClientMessage(Component.translatable("message.icraft.dock.link_first"), true);
            return;
        }
        if (!dockPos.dimension().equals(level.dimension())
                || !(level.getBlockEntity(dockPos.pos()) instanceof DockBlockEntity dock)) {
            player.displayClientMessage(Component.translatable("message.icraft.dock.missing"), true);
            return;
        }

        Multiblock.Result big = Multiblock.find(level, clicked);
        BlockPos anchor = big != null ? big.anchor() : clicked;

        if (!dock.hasSpeaker(anchor)) {
            if (dock.isFull()) {
                player.displayClientMessage(Component.translatable("message.icraft.dock.speakers_full",
                        DockBlockEntity.MAX_SPEAKERS), true);
                return;
            }
            if (Vec3.atCenterOf(anchor).distanceTo(Vec3.atCenterOf(dockPos.pos())) > DockBlockEntity.MAX_SPEAKER_DISTANCE) {
                player.displayClientMessage(Component.translatable("message.icraft.dock.too_far",
                        (int) DockBlockEntity.MAX_SPEAKER_DISTANCE), true);
                return;
            }
        }
        boolean added = dock.toggleSpeaker(anchor, big);
        player.displayClientMessage(Component.translatable(
                added ? "message.icraft.dock.speaker_added" : "message.icraft.dock.speaker_removed",
                dock.getSpeakers().size(), DockBlockEntity.MAX_SPEAKERS), true);
    }
}
