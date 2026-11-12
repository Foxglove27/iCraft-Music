package net.foxglove.icraft.client;

import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.client.audio.FileSoundInstance;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public class IpodPlayer {
    private static final float SPEAKER_VOLUME = 1.0F; // range = volume x 16 blocks

    private static SoundInstance current;
    private static ItemStack currentDisc = ItemStack.EMPTY; // empty while a file is playing
    @Nullable
    private static BlockPos currentSpeaker; // null when playing directly on the player

    public static void play(ItemStack disc, @Nullable BlockPos speaker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        JukeboxSong.fromStack(mc.level.registryAccess(), disc).ifPresent(song -> {
            SoundEvent sound = song.value().soundEvent().value();
            BlockPos used = validSpeaker(mc, speaker);

            SoundInstance instance;
            if (used != null) {
                Vec3 pos = Vec3.atCenterOf(used);
                instance = new SimpleSoundInstance(sound.getLocation(), SoundSource.RECORDS,
                        SPEAKER_VOLUME, 1.0F, SoundInstance.createUnseededRandom(), false, 0,
                        SoundInstance.Attenuation.LINEAR, pos.x, pos.y, pos.z, false);
            } else {
                instance = SimpleSoundInstance.forMusic(sound);
            }
            start(instance, disc.copy(), used, song.value().description());
        });
    }

    public static void playFile(Path file, @Nullable BlockPos speaker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos used = validSpeaker(mc, speaker);
        Vec3 pos = used != null ? Vec3.atCenterOf(used) : null;
        start(new FileSoundInstance(file, pos, SPEAKER_VOLUME), ItemStack.EMPTY, used,
                Component.literal(LocalMusic.displayName(file)));
    }

    private static void start(SoundInstance instance, ItemStack disc, @Nullable BlockPos speaker, Component name) {
        Minecraft mc = Minecraft.getInstance();
        stop();
        mc.getMusicManager().stopPlaying();
        current = instance;
        currentDisc = disc;
        currentSpeaker = speaker;
        mc.getSoundManager().play(instance);
        mc.gui.setNowPlaying(name);
    }

    @Nullable
    private static BlockPos validSpeaker(Minecraft mc, @Nullable BlockPos speaker) {
        return (speaker != null && mc.level.getBlockState(speaker).is(ModBlocks.SPEAKER.get())) ? speaker : null;
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

        if (player == null || mc.level == null || !holdsIpod(player)) {
            stop();
            return;
        }

        if (currentSpeaker != null
                && mc.level.hasChunkAt(currentSpeaker)
                && !mc.level.getBlockState(currentSpeaker).is(ModBlocks.SPEAKER.get())) {
            stop();
        }
    }

    private static boolean holdsIpod(Player player) {
        if (carriesPlaying(player.containerMenu.getCarried())) return true; // iPod on the cursor
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (carriesPlaying(inv.getItem(i))) return true;
        }
        return false;
    }

    private static boolean carriesPlaying(ItemStack stack) {
        if (!(stack.getItem() instanceof IpodItem)) return false;
        if (currentDisc.isEmpty()) return true; // a file is playing: any iPod will do
        for (ItemStack disc : IpodItem.getDiscs(stack)) {
            if (ItemStack.isSameItemSameComponents(disc, currentDisc)) return true;
        }
        return false;
    }
}