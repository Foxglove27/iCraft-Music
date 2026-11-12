package net.foxglove.icraft.client;

import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class IpodPlayer {
    private static final float SPEAKER_VOLUME = 1.0F; // range = volume x 16 blocks

    private static SoundInstance current;
    private static ItemStack currentDisc = ItemStack.EMPTY;
    @Nullable
    private static BlockPos currentSpeaker; // null when playing directly on the player

    public static void play(ItemStack disc, @Nullable BlockPos speaker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        JukeboxSong.fromStack(mc.level.registryAccess(), disc).ifPresent(song -> {
            stop();
            mc.getMusicManager().stopPlaying();

            SoundEvent sound = song.value().soundEvent().value();
            if (speaker != null && mc.level.getBlockState(speaker).is(ModBlocks.SPEAKER.get())) {
                Vec3 pos = Vec3.atCenterOf(speaker);
                current = new SimpleSoundInstance(sound.getLocation(), SoundSource.RECORDS,
                        SPEAKER_VOLUME, 1.0F, SoundInstance.createUnseededRandom(), false, 0,
                        SoundInstance.Attenuation.LINEAR, pos.x, pos.y, pos.z, false);
                currentSpeaker = speaker;
            } else {
                current = SimpleSoundInstance.forMusic(sound);
                currentSpeaker = null;
            }

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
        currentSpeaker = null;
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
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.level == null || !holdsPlayingDisc(player)) {
            stop();
            return;
        }

        // speaker was broken or replaced: stop (only if that chunk is actually loaded)
        if (currentSpeaker != null
                && mc.level.hasChunkAt(currentSpeaker)
                && !mc.level.getBlockState(currentSpeaker).is(ModBlocks.SPEAKER.get())) {
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