package net.foxglove.icraft.client;

import net.foxglove.icraft.item.IpodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

public class IpodPlayer {
    private static SoundInstance current;
    private static ItemStack currentDisc = ItemStack.EMPTY;

    public static void play(ItemStack disc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        JukeboxSong.fromStack(mc.level.registryAccess(), disc).ifPresent(song -> {
            stop();
            mc.getMusicManager().stopPlaying();
            current = SimpleSoundInstance.forMusic(song.value().soundEvent().value());
            currentDisc = disc.copy();
            mc.getSoundManager().play(current);
            mc.gui.setNowPlaying(song.value().description());
        });
    }

    public static void stop() {
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            current = null;
        }
        currentDisc = ItemStack.EMPTY;
    }

    public static void stopIfPlaying(ItemStack removed) {
        if (current != null && ItemStack.isSameItemSameComponents(currentDisc, removed)) {
            stop();
        }
    }

    public static boolean isPlaying() {
        return current != null && Minecraft.getInstance().getSoundManager().isActive(current);
    }

    // Called every client tick
    public static void tick() {
        if (current == null) return;
        Player player = Minecraft.getInstance().player;
        if (player == null || !holdsPlayingDisc(player)) {
            stop();
        }
    }

    private static boolean holdsPlayingDisc(Player player) {
        if (carriesDisc(player.containerMenu.getCarried())) return true; // iPod on the cursor
        Inventory inv = player.getInventory(); // main slots, armor, and offhand
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (carriesDisc(inv.getItem(i))) return true;
        }
        return false;
    }

    private static boolean carriesDisc(ItemStack stack) {
        if (!(stack.getItem() instanceof IpodItem)) return false;
        for (ItemStack disc : IpodItem.getDiscs(stack)) {
            if (ItemStack.isSameItemSameComponents(disc, currentDisc)) return true;
        }
        return false;
    }
}