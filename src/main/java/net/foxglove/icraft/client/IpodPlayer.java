package net.foxglove.icraft.client;

import com.mojang.blaze3d.audio.Channel;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.client.audio.IpodSoundInstance;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

public class IpodPlayer {
    private static final float SPEAKER_VOLUME = 1.0F; // range = volume x 16 blocks

    private static SoundInstance current;
    private static ItemStack currentDisc = ItemStack.EMPTY; // empty while a file is playing
    @Nullable
    private static BlockPos currentSpeaker; // null when playing directly on the player

    private static boolean paused = false;
    // true when we paused it because the iPod left your inventory (so we can resume it on return)
    private static boolean autoPaused = false;

    // The list of tracks the current song came from, and our position in it
    private static List<Runnable> queue = List.of();
    private static int queueIndex = -1;

    public static Component getNowPlaying() {
        return currentName;
    }

    public static Component getNowPlayingSubtitle() {
        return currentSubtitle;
    }

    @Nullable
    public static Path getNowPlayingCover() {
        return currentCover;
    }

    public static ItemStack getNowPlayingDisc() {
        return currentDisc;
    }

    // What is playing right now, for the on-screen display
    private static Component currentName = Component.empty();
    private static Component currentSubtitle = Component.empty();
    @Nullable
    private static Path currentCover;

    public static void play(ItemStack disc, @Nullable BlockPos speaker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        JukeboxSong.fromStack(mc.level.registryAccess(), disc).ifPresent(song -> {
            SoundEvent sound = song.value().soundEvent().value();
            BlockPos used = validSpeaker(mc, speaker);
            Vec3 pos = used != null ? Vec3.atCenterOf(used) : null;

            start(IpodSoundInstance.forDisc(sound, pos, SPEAKER_VOLUME), disc.copy(), used,
                    song.value().description(),
                    Component.translatable("screen.icraft.ipod.discs"),
                    null);
        });
    }

    public static void playFile(Path file, @Nullable BlockPos speaker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos used = validSpeaker(mc, speaker);
        Vec3 pos = used != null ? Vec3.atCenterOf(used) : null;

        start(IpodSoundInstance.forFile(file, pos, SPEAKER_VOLUME), ItemStack.EMPTY, used,
                Component.literal(LocalMusic.displayName(file)),
                LocalMusic.albumNameFor(file),
                LocalMusic.coverFor(file));
    }

    private static void start(SoundInstance instance, ItemStack disc, @Nullable BlockPos speaker,
                              Component name, Component subtitle, @Nullable Path cover) {
        Minecraft mc = Minecraft.getInstance();
        endCurrent(); // ends any current sound, but keeps the track list
        mc.getMusicManager().stopPlaying();
        current = instance;
        currentDisc = disc;
        currentSpeaker = speaker;
        currentName = name;
        currentSubtitle = subtitle;
        currentCover = cover;
        mc.getSoundManager().play(instance);
    }

    // Ends the current sound but keeps the track list (used when switching tracks)
    private static void endCurrent() {
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            current = null;
        }
        currentDisc = ItemStack.EMPTY;
        currentSpeaker = null;
        currentName = Component.empty();
        currentSubtitle = Component.empty();
        currentCover = null;
        paused = false;
        autoPaused = false;
    }

    @Nullable
    private static BlockPos validSpeaker(Minecraft mc, @Nullable BlockPos speaker) {
        return (speaker != null && mc.level.getBlockState(speaker).is(ModBlocks.SPEAKER.get())) ? speaker : null;
    }

    // Ends the song completely and forgets the track list.
    // Playing again starts from the beginning.
    public static void stop() {
        endCurrent();
        queue = List.of();
        queueIndex = -1;
    }

    // Freezes the song exactly where it is
    public static void pause() {
        if (current == null) return;
        paused = true;
        autoPaused = false;
        setChannelPaused(true);
    }

    // Carries on from exactly where it was frozen
    public static void resume() {
        paused = false;
        autoPaused = false;
        setChannelPaused(false);
    }

    public static void togglePause() {
        if (!canTogglePause()) return;
        if (paused) {
            resume();
        } else {
            pause();
        }
    }

    // Starts a track and remembers the list it came from, so Next/Previous know where to go
    public static void playQueue(List<Runnable> tracks, int index) {
        queue = tracks;
        queueIndex = index;
        tracks.get(index).run();
    }

    public static void next() {
        skip(1);
    }

    public static void previous() {
        skip(-1);
    }

    private static void skip(int step) {
        if (queue.isEmpty()) return;
        // floorMod keeps the position inside the list and wraps around at both ends
        queueIndex = Math.floorMod(queueIndex + step, queue.size());
        queue.get(queueIndex).run();
    }

    // Pause/Play only works while a song is loaded
    public static boolean canTogglePause() {
        return isPlaying();
    }

    public static boolean isPaused() {
        return paused;
    }

    public static void stopIfPlaying(ItemStack removed) {
        if (current != null && ItemStack.isSameItemSameComponents(currentDisc, removed)) {
            stop();
        }
    }

    public static boolean isPlaying() {
        return current != null && Minecraft.getInstance().getSoundManager().isActive(current);
    }

    // True while any track is loaded (even paused or still loading), so the HUD can stay on
    // through Next/Previous transitions. Becomes false only once we stop completely.
    public static boolean hasTrack() {
        return !currentName.getString().isEmpty();
    }

    // True if this is the sound the player is currently tracking.
    // A sound that is no longer current has been replaced or stopped, and should end itself.
    public static boolean isCurrent(SoundInstance sound) {
        return sound == current;
    }

    // Pauses or unpauses the real audio channel that our sound is playing on.
    // This reaches into the sound engine, which is why we needed the access transformer.
    private static void setChannelPaused(boolean pause) {
        if (current == null) return;

        SoundEngine engine = Minecraft.getInstance().getSoundManager().soundEngine;
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(current);
        if (handle == null) return; // the sound hasn't started yet (still loading)

        // 'execute' runs our request on the sound engine's own thread
        if (pause) {
            handle.execute(Channel::pause);
        } else {
            handle.execute(Channel::unpause);
        }
    }

    // Called every tick by the playing sound itself. 'who' is the sound calling us.
    public static void tick(SoundInstance who) {
        if (who != current) return; // an old sound that has already been replaced

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.level == null) {
            stop();
            return;
        }

        // iPod gone from the inventory: pause. iPod back: resume (only if WE paused it).
        boolean hasIpod = holdsIpod(player);
        if (!hasIpod && !paused) {
            paused = true;
            autoPaused = true;
        } else if (hasIpod && autoPaused) {
            paused = false;
            autoPaused = false;
            setChannelPaused(false);
        }

        // Keep the real channel matching our flag. This also covers the game's own pause
        // menu, which unpauses every channel when it closes, including ours.
        if (paused) {
            setChannelPaused(true);
        }

        // speaker was broken or replaced: stop (only if that chunk is actually loaded)
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