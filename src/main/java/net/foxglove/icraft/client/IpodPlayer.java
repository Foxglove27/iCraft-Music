package net.foxglove.icraft.client;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.client.audio.AudiobookProgress;
import net.foxglove.icraft.client.audio.IpodSoundInstance;
import net.foxglove.icraft.client.audio.PcmAudioStream;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.slf4j.Logger;

public class IpodPlayer {
    private static final float SPEAKER_VOLUME = 1.0F; // range = volume x 16 blocks

    private static SoundInstance current;
    private static ItemStack currentDisc = ItemStack.EMPTY; // empty while a file is playing
    @Nullable
    private static BlockPos currentSpeaker; // null when playing directly on the player
    // The link of the playing iPod at the moment the track started (null = none). When the iPod's link
    // differs from this, the track restarts on the new output. Compared as links, not as usable speakers,
    // so walking away from a speaker (unloaded chunk) never counts as a change.
    @Nullable
    private static GlobalPos currentLink;

    private static boolean paused = false;
    // true when we paused it because the iPod left your inventory (so we can resume it on return)
    private static boolean autoPaused = false;

    // The ID of the iPod that started the current song. The song belongs to that iPod:
    // when it leaves the inventory the music pauses, even if you carry other iPods.
    @Nullable
    private static UUID currentIpod;

    // The list of tracks the current song came from, and our position in it
    private static List<Runnable> queue = List.of();
    // The order the tracks are played in: indices into 'queue'. 0..n-1 normally, shuffled with shuffle on.
    private static int[] order = new int[0];
    // Our position in 'order' (not in 'queue')
    private static int queueIndex = -1;
    private static final Random RANDOM = new Random();
    // The list being played is an audiobook: chapters in order, no shuffle or repeat
    private static boolean queueIsBook = false;

    // ---- audiobook chapter and position tracking ----
    // What the current track is, when it is an audiobook chapter (null for music and discs)
    // What to save the position under: an audiobook chapter always, a song only while a speaker is linked.
    // 'itemStartSeconds' is where the item begins inside the file (0 unless it is a chapter marker in an .m4b).
    private record ProgressTarget(String bookKey, String itemKey, double itemStartSeconds, @Nullable String nextKey) {}
    @Nullable
    private static ProgressTarget currentProgress;
    private static double currentItemStart = 0;   // the item's start and end inside the file (end -1 = file end)
    private static double currentItemEnd = -1;
    private static boolean chapterFinished = false; // it ended on its own: its progress was cleared, do not save again
    // Position inside the file, in seconds, to start the next file track at (set for restarts); -1 = none
    private static double pendingStartSeconds = -1;
    // Last position read from OpenAL: seconds into the file, and which sound it belongs to
    private static volatile double lastPositionSeconds = 0;
    @Nullable
    private static volatile SoundInstance lastPositionOwner;
    private static int ticksSinceRefresh = 0;
    private static int ticksSinceSave = 0;
    private static final int REFRESH_TICKS = 5;   // ask OpenAL where we are 4 times a second
    private static final int SAVE_TICKS = 200;    // save audiobook progress every 10 seconds
    private static final double RESUME_REWIND_SECONDS = 2.0;

    // True once the current sound has really started playing (it is only ticked then).
    // Until then "not active" just means "still loading".
    private static boolean started = false;
    // Tracks in a row that failed to play. When it reaches the list size, everything is broken: stop.
    private static int consecutiveFailures = 0;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    // Called on the main thread when a track could not be loaded
    // (corrupt file, file too large, ffmpeg missing...)
    public static void onLoadFailed(SoundInstance who, Throwable error) {
        if (who != current) return; // an old track nobody is waiting for anymore

        Throwable cause = error.getCause() != null ? error.getCause() : error;
        LOGGER.warn("Could not play {}", currentName.getString(), cause);

        showLoadFailed();
        advanceAfterEnd(true);
    }

    // Called once per client tick (see ClientEvents). Starts the next track when one has finished.
    public static void clientTick() {
        if (current == null || !started || paused) return;
        Minecraft mc = Minecraft.getInstance();

        // The iPod was linked, unlinked or linked to another speaker: restart the track on the new output
        if (currentIpod != null && mc.player != null && queueIndex >= 0 && queueIndex < order.length) {
            ItemStack ipod = findIpod(mc.player, currentIpod);
            if (ipod != null && !Objects.equals(ipod.get(ModDataComponents.LINKED_SPEAKER.get()), currentLink)) {
                if (current instanceof IpodSoundInstance sound && sound.isFile()) {
                    refreshPosition();
                    pendingStartSeconds = positionSeconds(); // carry on at the same second on the new output
                }
                queue.get(order[queueIndex]).run(); // resolves the link again and records it, so this happens once
                return;
            }
        }

        if (mc.getSoundManager().isActive(current)) {
            if (++ticksSinceRefresh >= REFRESH_TICKS) {
                ticksSinceRefresh = 0;
                refreshPosition();
            }
            if (currentProgress != null && ++ticksSinceSave >= SAVE_TICKS) {
                ticksSinceSave = 0;
                saveProgress();
            }
            return; // still playing
        }

        if (current instanceof IpodSoundInstance sound && sound.isFile()) {
            if (sound.streamFailed()) {
                showLoadFailed();
                advanceAfterEnd(true);
            } else if (sound.streamEnded()) {
                advanceAfterEnd(false);
            }
            // else: the sound engine stopped it mid-song (for example an audio device switch). Leave it.
        } else {
            advanceAfterEnd(false); // a vanilla disc has no end-of-stream flag; inactive after starting = finished
        }
    }

    // The track is over (finished or failed): decide what plays next.
    // Repeat One plays the same track again; otherwise the next one in the play order. After the last
    // one, Repeat Off stops and the other modes start over. A failed track is never repeated.
    private static void advanceAfterEnd(boolean failed) {
        consecutiveFailures = failed ? consecutiveFailures + 1 : 0;
        if (queue.isEmpty() || consecutiveFailures >= queue.size()) {
            stop(); // clears the track, so the HUD disappears too
            return;
        }

        if (!failed) finishChapter(); // an audiobook chapter that played to the end forgets its position

        if (queueIsBook) { // chapters play in order and stop after the last; shuffle and repeat do not apply
            int following = queueIndex + 1;
            if (following >= order.length) {
                stop();
                return;
            }
            queueIndex = following;
            queue.get(order[following]).run();
            return;
        }

        RepeatMode repeat = IpodSettings.repeat();
        if (!failed && repeat == RepeatMode.ONE) {
            queue.get(order[queueIndex]).run();
            return;
        }

        int next = queueIndex + 1;
        if (next >= order.length) {
            if (repeat == RepeatMode.OFF) {
                stop();
                return;
            }
            // start over; with shuffle on, a fresh random order that does not begin with the track just played
            if (IpodSettings.shuffle()) order = shuffledOrder(-1, order[queueIndex]);
            next = 0;
        }
        queueIndex = next;
        queue.get(order[next]).run();
    }

    // The play order for 'size' tracks. 'first' (or -1) is played first; the rest is shuffled.
    // 'notFirst' (or -1) is a track that must not come first (the one that just played).
    private static int[] shuffledOrder(int first, int notFirst) {
        int n = queue.size();
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = i;

        int from = 0;
        if (first >= 0) { // put the chosen track in front and only shuffle the rest
            result[first] = 0;
            result[0] = first;
            from = 1;
        }
        for (int i = n - 1; i > from; i--) { // Fisher-Yates
            int j = from + RANDOM.nextInt(i - from + 1);
            int tmp = result[i];
            result[i] = result[j];
            result[j] = tmp;
        }
        if (first < 0 && n > 1 && result[0] == notFirst) {
            int j = 1 + RANDOM.nextInt(n - 1);
            result[0] = result[j];
            result[j] = notFirst;
        }
        return result;
    }

    // Builds 'order' for the list, starting at track 'index'. Returns our position in it.
    private static int buildOrder(int index) {
        if (IpodSettings.shuffle() && !queueIsBook) {
            order = shuffledOrder(index, -1);
            return 0;
        }
        order = new int[queue.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        return index;
    }

    // Called when shuffle is switched on or off: the current track stays current, what follows changes
    public static void shuffleChanged() {
        if (queueIsBook || queue.isEmpty() || queueIndex < 0 || queueIndex >= order.length) return;
        queueIndex = buildOrder(order[queueIndex]);
    }

    private static void showLoadFailed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.icraft.ipod.load_failed", currentName), true);
        }
    }
    
    public static void play(ItemStack disc, @Nullable UUID ipod) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        GlobalPos link = linkedSpeakerOf(ipod);

        JukeboxSong.fromStack(mc.level.registryAccess(), disc).ifPresent(song -> {
            SoundEvent sound = song.value().soundEvent().value();
            BlockPos used = validSpeaker(mc, link);
            Vec3 pos = used != null ? Vec3.atCenterOf(used) : null;

            start(IpodSoundInstance.forDisc(sound, pos, SPEAKER_VOLUME), disc.copy(), used, link,
                    song.value().description(),
                    Component.translatable("screen.icraft.ipod.discs"),
                    null, ipod);
        });
    }

    public static void playFile(Path file, @Nullable UUID ipod) {
        playFileInternal(file, ipod, Component.literal(LocalMusic.displayName(file)),
                LocalMusic.albumNameFor(file), LocalMusic.coverFor(file), null, null, null);
    }

    /**
     * Plays an audiobook chapter from where it was left (a little earlier), or from its start.
     * {@code nextKey} is the chapter that follows, so finishing this one can move the book's "last" marker.
     */
    public static void playChapter(LocalMusic.Chapter chapter, String bookKey, @Nullable String nextKey,
                                   @Nullable UUID ipod) {
        playFileInternal(chapter.file(), ipod, chapter.title(),
                LocalMusic.albumNameFor(chapter.file()), LocalMusic.coverFor(chapter.file()),
                chapter, bookKey, nextKey);
    }

    private static void playFileInternal(Path file, @Nullable UUID ipod, Component name, Component subtitle,
                                         @Nullable Path cover, @Nullable LocalMusic.Chapter chapter,
                                         @Nullable String bookKey, @Nullable String nextKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        GlobalPos link = linkedSpeakerOf(ipod);
        BlockPos used = validSpeaker(mc, link);
        Vec3 pos = used != null ? Vec3.atCenterOf(used) : null;

        // Progress is kept for audiobook chapters, and for songs while the iPod is linked to a speaker
        ProgressTarget target = null;
        if (chapter != null && bookKey != null) {
            target = new ProgressTarget(bookKey, chapter.key(), chapter.startSeconds(), nextKey);
        } else if (link != null) {
            target = new ProgressTarget(AudiobookProgress.MUSIC, LocalMusic.key(file), 0, null);
        }
        double itemStart = chapter != null ? chapter.startSeconds() : 0;
        double endSeconds = chapter != null ? chapter.endSeconds() : -1;

        // where in the file to begin: an exact position from a restart or skip, else the saved one
        double startSeconds = itemStart;
        if (pendingStartSeconds >= 0) {
            startSeconds = pendingStartSeconds;
        } else if (target != null) {
            double saved = AudiobookProgress.get(target.bookKey(), target.itemKey());
            startSeconds = itemStart + Math.max(0, saved - RESUME_REWIND_SECONDS);
        }
        pendingStartSeconds = -1;
        startSeconds = Math.max(startSeconds, itemStart);

        start(IpodSoundInstance.forFile(file, pos, SPEAKER_VOLUME, startSeconds, endSeconds), ItemStack.EMPTY, used, link,
                name, subtitle, cover, ipod);
        currentProgress = target; // after start(), which clears the previous one
        currentItemStart = itemStart;
        currentItemEnd = endSeconds;
        lastPositionSeconds = startSeconds;
        lastPositionOwner = current;
    }

    // ---- position and audiobook progress ----

    /** Where the current file track is, in seconds into the file (the last value read from OpenAL). */
    private static double positionSeconds() {
        if (current != null && current == lastPositionOwner) return lastPositionSeconds;
        return 0;
    }

    // Asks the sound engine's thread where playback is and remembers it. Cheap; called a few times a second.
    private static void refreshPosition() {
        if (!(current instanceof IpodSoundInstance sound) || !sound.isFile()) return;
        PcmAudioStream stream = sound.stream();
        if (stream == null) return;
        SoundEngine engine = Minecraft.getInstance().getSoundManager().soundEngine;
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(current);
        if (handle == null) return;

        SoundInstance owner = current;
        handle.execute(channel -> {
            int queued = AL10.alGetSourcei(channel.source, AL10.AL_BUFFERS_QUEUED);
            int offset = AL10.alGetSourcei(channel.source, AL11.AL_SAMPLE_OFFSET);
            lastPositionSeconds = (double) stream.playedFrames(queued, offset) / stream.sampleRate();
            lastPositionOwner = owner;
        });
    }

    /** True while a file (music or audiobook chapter) is playing, so holding Next/Previous can skip within it. */
    public static boolean canSkipSeconds() {
        return current instanceof IpodSoundInstance sound && sound.isFile() && started;
    }

    /**
     * Jumps forward or back within the current file by {@code delta} seconds (clamped to its chapter or
     * file). Jumping past the end counts as the track ending. Ignored while paused. The sound restarts at
     * the new position, like a relink.
     */
    public static void skipSeconds(double delta) {
        if (!canSkipSeconds() || paused || queue.isEmpty() || queueIndex < 0 || queueIndex >= order.length) return;
        IpodSoundInstance sound = (IpodSoundInstance) current;

        double target = Math.max(currentItemStart, positionSeconds() + delta);
        double end = currentItemEnd;
        PcmAudioStream stream = sound.stream();
        if (end < 0 && stream != null && stream.lengthFrames() > 0) {
            end = (double) stream.lengthFrames() / stream.sampleRate();
        }
        if (end >= 0 && target >= end - 0.5) {
            advanceAfterEnd(false); // skipped to the end: the track is over
            return;
        }
        pendingStartSeconds = target;
        queue.get(order[queueIndex]).run();
    }

    // Writes the current position to the progress file (audiobook chapters, and songs while linked)
    private static void saveProgress() {
        ProgressTarget target = currentProgress;
        if (target == null || !started || chapterFinished) return;
        double inItem = Math.max(0, positionSeconds() - target.itemStartSeconds());
        AudiobookProgress.save(target.bookKey(), target.itemKey(), inItem);
    }

    // The chapter played to the end: forget its position and point the book at the next chapter
    private static void finishChapter() {
        ProgressTarget target = currentProgress;
        if (target == null || chapterFinished) return;
        chapterFinished = true;
        AudiobookProgress.finish(target.bookKey(), target.itemKey(), target.nextKey());
    }

    private static void start(SoundInstance instance, ItemStack disc, @Nullable BlockPos speaker,
                              @Nullable GlobalPos link, Component name, Component subtitle, @Nullable Path cover,
                              @Nullable UUID ipod) {
        Minecraft mc = Minecraft.getInstance();
        endCurrent(); // ends any current sound, but keeps the track list
        mc.getMusicManager().stopPlaying();
        current = instance;
        currentDisc = disc;
        currentSpeaker = speaker;
        currentLink = link;
        currentName = name;
        currentSubtitle = subtitle;
        currentCover = cover;
        currentIpod = ipod;
        mc.getSoundManager().play(instance);
    }

    // Ends the current sound but keeps the track list (used when switching tracks)
    private static void endCurrent() {
        refreshPosition();
        saveProgress(); // an audiobook chapter remembers where it was left
        currentProgress = null;
        chapterFinished = false;
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            current = null;
        }
        currentDisc = ItemStack.EMPTY;
        currentSpeaker = null;
        currentLink = null;
        currentName = Component.empty();
        currentSubtitle = Component.empty();
        currentCover = null;
        currentIpod = null;
        paused = false;
        autoPaused = false;
        started = false;
        ticksSinceRefresh = 0;
        ticksSinceSave = 0;
    }

    @Nullable
    private static BlockPos validSpeaker(Minecraft mc, @Nullable GlobalPos link) {
        if (link == null || !link.dimension().equals(mc.level.dimension())) return null;
        return mc.level.getBlockState(link.pos()).is(ModBlocks.SPEAKER.get()) ? link.pos() : null;
    }

    // The speaker the iPod with this ID is linked to right now (read from the stack in the player's inventory)
    @Nullable
    private static GlobalPos linkedSpeakerOf(@Nullable UUID ipod) {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player != null ? findIpod(player, ipod) : null;
        return stack != null ? stack.get(ModDataComponents.LINKED_SPEAKER.get()) : null;
    }

    // The iPod with this ID in the inventory (or on the cursor), or null
    @Nullable
    private static ItemStack findIpod(Player player, @Nullable UUID id) {
        if (id == null) return null;
        ItemStack carried = player.containerMenu.getCarried();
        if (isIpodWithId(carried, id)) return carried;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isIpodWithId(inv.getItem(i), id)) return inv.getItem(i);
        }
        return null;
    }

    private static boolean isIpodWithId(ItemStack stack, UUID id) {
        return stack.getItem() instanceof IpodItem && id.equals(stack.get(ModDataComponents.IPOD_ID.get()));
    }

    /** The position in the list (not the play order) of the track that is playing, or -1. For the GUI highlight. */
    public static int playingQueueIndex() {
        return (current != null && queueIndex >= 0 && queueIndex < order.length) ? order[queueIndex] : -1;
    }

    /** True if the player is playing from exactly this list of tracks. */
    public static boolean isQueue(List<Runnable> tracks) {
        return queue == tracks;
    }

    // Ends the song completely and forgets the track list.
    // Playing again starts from the beginning.
    public static void stop() {
        endCurrent();
        queue = List.of();
        order = new int[0];
        queueIndex = -1;
    }

    // Freezes the song exactly where it is
    public static void pause() {
        if (current == null) return;
        refreshPosition();
        saveProgress();
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
        playQueue(tracks, index, false);
    }

    /** Starts a list. {@code audiobook}: chapters in order, no shuffle or repeat. */
    public static void playQueue(List<Runnable> tracks, int index, boolean audiobook) {
        queue = tracks;
        queueIsBook = audiobook;
        consecutiveFailures = 0;
        queueIndex = buildOrder(index);
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
        queueIndex = Math.floorMod(queueIndex + step, order.length);
        queue.get(order[queueIndex]).run();
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

        if (!started) {
            started = true;
            consecutiveFailures = 0; // it loaded and plays, so earlier failures no longer count
        }

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
            saveProgress();
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

        // The song belongs to the specific iPod that started it
        if (currentIpod != null) {
            return currentIpod.equals(stack.get(ModDataComponents.IPOD_ID.get()));
        }

        // Fallback for an iPod that had no ID yet when the song started
        if (currentDisc.isEmpty()) return true; // a file is playing: any iPod will do
        return IpodItem.hasDisc(stack, currentDisc);
    }
}