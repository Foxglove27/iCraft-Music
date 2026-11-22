package net.foxglove.icraft.client;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.block.Multiblock;
import net.foxglove.icraft.client.audio.ChannelMode;
import net.foxglove.icraft.client.audio.AudiobookProgress;
import net.foxglove.icraft.client.audio.IpodSoundInstance;
import net.foxglove.icraft.client.audio.PcmAudioStream;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.client.audio.DiscAudio;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.slf4j.Logger;

public class IpodPlayer {

    private static SoundInstance current;
    private static ItemStack currentDisc = ItemStack.EMPTY; // empty while a file is playing

    // The speakers the current track plays through; empty = straight into the player's ears
    private static List<DockOutputs.Output> currentOutputs = List.of();
    // The sounds of the speakers after the first. 'current' is the first one and drives everything; these
    // are started, paused, resumed and stopped together with it.
    private static List<SoundInstance> extras = List.of();
    // The dock the list was started from (null = an iPod the player holds). The music belongs to that dock.
    @Nullable
    private static GlobalPos queueDock;
    private static int ticksSinceOutputCheck = 0;
    private static final int OUTPUT_CHECK_TICKS = 10; // look for changed speakers twice a second

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
    @Nullable
    private static String currentItemKey; // the playing file's key (a song or a chapter), for the live time on its row
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
    // direction of the last skip (-1 = Previous): a broken track then skips further back, not forward
    private static int lastStep = 1;

    public static Component getNowPlaying() {
        return DockControl.isActive() ? DockControl.currentName() : currentName;
    }

    public static Component getNowPlayingSubtitle() {
        return DockControl.isActive() ? DockControl.currentSubtitle() : currentSubtitle;
    }

    @Nullable
    public static Path getNowPlayingCover() {
        return DockControl.isActive() ? DockControl.currentCover() : currentCover;
    }

    public static ItemStack getNowPlayingDisc() {
        return DockControl.isActive() ? DockControl.currentDiscStack() : currentDisc;
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
        tickPickup();
        if (restartPending) { // asked from a sound's tick; done here, outside the sound engine's loop
            restartPending = false;
            if (current != null) restartAtSamePlace();
            return;
        }
        playPendingWhenFree();
        if (current == null || !started || paused) return;
        Minecraft mc = Minecraft.getInstance();

        // The dock's speakers changed (added, removed, a multiblock completed or broken): carry on at the same
        // second on the new set of speakers
        if (queueDock != null && queueIndex >= 0 && queueIndex < order.length
                && ++ticksSinceOutputCheck >= OUTPUT_CHECK_TICKS) {
            ticksSinceOutputCheck = 0;
            if (mc.level != null && queueDock.dimension().equals(mc.level.dimension())
                    && mc.level.hasChunkAt(queueDock.pos())
                    && !outputsFor(mc, currentIpod, currentOutputs).equals(currentOutputs)) {
                if (current instanceof IpodSoundInstance sound && sound.isFile()) {
                    refreshPosition();
                    pendingStartSeconds = positionSeconds();
                }
                queue.get(order[queueIndex]).run(); // works out the outputs again and records them
                return;
            }
        }

        if (mc.getSoundManager().isActive(current)) {
            revives = 0;
            reviveWait = 0;
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
            } else if (revives < MAX_REVIVES && ++reviveWait >= REVIVE_TICKS * (1 + revives)) {
                // The sound engine dropped it mid-song (audio device switch, a volume set to 0 and back):
                // carry on at the same second. Tries a few times, further apart each time, then gives up.
                reviveWait = 0;
                revives++;
                LOGGER.info("iPod: the sound stopped before the track ended, restarting it (try {} of {})", revives, MAX_REVIVES);
                restartAtSamePlace();
            }
        } else {
            advanceAfterEnd(false); // a vanilla disc has no end-of-stream flag; inactive after starting = finished
        }
    }

    private static boolean restartPending;
    // revives of a sound the engine dropped mid-song (see clientTick)
    private static final int MAX_REVIVES = 5;
    private static final int REVIVE_TICKS = 20;
    private static int revives;
    private static int reviveWait;

    // Starts the current track again where it is, on whatever outputs it works out now (speakers or the player's ears)
    /**
     * A setting that changes the sound itself (bass boost) was changed: the playing track starts again at the same
     * second, because the engine has a few seconds of the old sound queued. Only for tracks we decode; not while paused.
     */
    public static void audioSettingsChanged() {
        if (paused || !(current instanceof IpodSoundInstance sound) || !sound.isFile()) return;
        restartAtSamePlace();
    }

    private static void restartAtSamePlace() {
        if (queueIndex < 0 || queueIndex >= order.length) return;
        if (current instanceof IpodSoundInstance sound && sound.isFile()) {
            refreshPosition();
            pendingStartSeconds = positionSeconds();
        }
        queue.get(order[queueIndex]).run(); // works out the outputs again and records them
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
        if (failed && lastStep < 0) { // Previous landed on a broken track: keep going back
            queueIndex = Math.floorMod(queueIndex - 1, order.length);
            queue.get(order[queueIndex]).run();
            return;
        }
        if (!failed) lastStep = 1;

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
        DockControl.modesChanged(); // a dock that plays its discs gets the new setting too
        if (queueIsBook || queue.isEmpty() || queueIndex < 0 || queueIndex >= order.length) return;
        queueIndex = buildOrder(order[queueIndex]);
    }

    private static void showLoadFailed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.icraft_music.ipod.load_failed", currentName), true);
        }
    }
    
    public static void play(ItemStack disc, @Nullable UUID ipod) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // In the player's own ears a disc is decoded by us, so its position is known (it can carry on in a dock);
        // other discs, or ones we cannot find the file of, use the game's own stream.
        if (queueDock == null && outputsFor(mc, ipod, List.of()).isEmpty()) {
            ResourceLocation path = JukeboxSong.fromStack(mc.level.registryAccess(), disc)
                    .map(s -> DiscAudio.resolve(s.value().soundEvent().value())).orElse(null);
            if (path != null) {
                play(disc, ipod, 0);
                return;
            }
        }
        playVanillaDisc(disc, ipod);
    }

    private static void playVanillaDisc(ItemStack disc, @Nullable UUID ipod) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        List<DockOutputs.Output> outputs = outputsFor(mc, ipod, List.of());

        JukeboxSong.fromStack(mc.level.registryAccess(), disc).ifPresent(song -> {
            SoundEvent sound = song.value().soundEvent().value();
            List<SoundInstance> sounds = new ArrayList<>();
            if (outputs.isEmpty()) {
                sounds.add(IpodSoundInstance.forDisc(sound, null, DockOutputs.SPEAKER_VOLUME));
            } else {
                for (DockOutputs.Output o : outputs) sounds.add(IpodSoundInstance.forDisc(sound, o.pos(), o.volume()));
            }
            start(sounds, disc.copy(), outputs,
                    song.value().description(),
                    Component.translatable("screen.icraft_music.ipod.discs"),
                    null, ipod);
        });
    }

    /** Like {@link #play(ItemStack, UUID)}, but starting {@code startSeconds} into the disc. */
    public static void play(ItemStack disc, @Nullable UUID ipod, double startSeconds) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Optional<Holder<JukeboxSong>> song = JukeboxSong.fromStack(mc.level.registryAccess(), disc);
        ResourceLocation path = song.map(s -> DiscAudio.resolve(s.value().soundEvent().value())).orElse(null);
        if (song.isEmpty() || path == null) {
            playVanillaDisc(disc, ipod); // cannot start in the middle: from the start
            return;
        }
        queueDock = null;
        IpodSoundInstance sound = IpodSoundInstance.forDiscAt(mode -> DiscAudio.open(path, mode),
                path.toString(), null, 1.0F, startSeconds);
        start(List.of(sound), disc.copy(), List.of(), song.get().value().description(),
                Component.translatable("screen.icraft_music.ipod.discs"), null, ipod);
    }

    // ---- taking the iPod out of a dock: what the dock was playing carries on in the player's ears ----

    private record Pickup(UUID ipod, ItemStack disc, double seconds, long deadline) {}
    @Nullable
    private static Pickup pickup;

    /**
     * The player is taking the iPod out of this dock (client side of the click). If the dock plays one of the
     * iPod's discs right now, it is started on the player's iPod at the same position as soon as the iPod arrives.
     */
    public static void pickupFromDock(DockBlockEntity dock) {
        Minecraft mc = Minecraft.getInstance();
        DockPlayback pb = dock.getPlayback();
        if (mc.level == null || !dock.hasIpod() || pb == null || !pb.isPlaying() || pb.isPaused()) return;
        UUID id = dock.getIpod().get(ModDataComponents.IPOD_ID.get());
        List<ItemStack> discs = IpodItem.getDiscs(dock.getIpod());
        int index = pb.currentDisc();
        if (id == null || index < 0 || index >= discs.size()) return;
        long now = mc.level.getGameTime();
        pickup = new Pickup(id, discs.get(index).copy(), pb.positionTicks(now) / 20.0, now + 100);
    }

    private static void tickPickup() {
        Pickup p = pickup;
        Minecraft mc = Minecraft.getInstance();
        if (p == null) return;
        if (mc.player == null || mc.level == null || mc.level.getGameTime() > p.deadline()) {
            pickup = null;
            return;
        }
        if (findIpod(mc.player, p.ipod()) != null) { // the iPod arrived in the inventory
            pickup = null;
            play(p.disc(), p.ipod(), p.seconds());
        }
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

        List<DockOutputs.Output> outputs = outputsFor(mc, ipod, List.of());

        // Progress is kept for audiobook chapters and podcast episodes only; songs always start from the beginning
        ProgressTarget target = null;
        if (chapter != null && bookKey != null) {
            target = new ProgressTarget(bookKey, chapter.key(), chapter.startSeconds(), nextKey);
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

        List<SoundInstance> sounds = new ArrayList<>();
        if (outputs.isEmpty()) {
            sounds.add(IpodSoundInstance.forFile(file, null, DockOutputs.SPEAKER_VOLUME, ChannelMode.STEREO,
                    startSeconds, endSeconds));
        } else {
            for (DockOutputs.Output o : outputs) {
                sounds.add(IpodSoundInstance.forFile(file, o.pos(), o.volume(), o.channel(), startSeconds, endSeconds));
            }
        }
        // the speakers in earshot start together (a far one that the engine skips is not waited for)
        if (mc.player != null && !outputs.isEmpty()) {
            List<SoundInstance> heard = new ArrayList<>();
            for (int i = 0; i < outputs.size(); i++) {
                if (DockOutputs.inRange(outputs.get(i), mc.player.position())) heard.add(sounds.get(i));
            }
            IpodSoundInstance.startTogether(heard);
        }
        start(sounds, ItemStack.EMPTY, outputs, name, subtitle, cover, ipod);
        currentProgress = target; // after start(), which clears the previous one
        currentItemKey = chapter != null ? chapter.key() : LocalMusic.key(file);
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

    /**
     * How far into its own chapter the item with this progress key is, or -1 if that item is not the one playing
     * here. Used by the iPod screen so the time on the playing row counts up while it is open.
     */
    public static double itemSeconds(String itemKey) {
        if (DockControl.isFilesActive()) return DockControl.itemSeconds(itemKey); // the dock plays it: the server clock knows
        String key = currentItemKey;
        if (key == null || !started || !key.equals(itemKey)) return -1; // also discs: they have no key
        return Math.max(0, positionSeconds() - currentItemStart);
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
        if (DockControl.isActive()) return true;
        return current instanceof IpodSoundInstance sound && sound.isFile() && started;
    }

    /**
     * Jumps forward or back within the current file by {@code delta} seconds (clamped to its chapter or
     * file). Jumping past the end counts as the track ending. Ignored while paused. The sound restarts at
     * the new position, like a relink.
     */
    public static void skipSeconds(double delta) {
        if (DockControl.isActive()) {
            if (!DockControl.isPaused()) DockControl.skipSeconds(delta);
            return;
        }
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

    private static void start(List<SoundInstance> instances, ItemStack disc, List<DockOutputs.Output> outputs,
                              Component name, Component subtitle, @Nullable Path cover, @Nullable UUID ipod) {
        Minecraft mc = Minecraft.getInstance();
        DockControl.stop(); // a disc the dock plays makes way for what the player starts now
        List<SoundInstance> retiring = new ArrayList<>(); // the old sounds still hold their audio channels for a moment
        if (current != null) retiring.add(current);
        retiring.addAll(extras);
        endCurrent(); // ends any current sound, but keeps the track list
        mc.getMusicManager().stopPlaying();
        for (SoundInstance instance : instances) {
            if (instance instanceof IpodSoundInstance ours) ours.setIpod(ipod); // so a mute of this iPod silences it
        }
        current = instances.get(0);
        extras = new ArrayList<>(instances.subList(1, instances.size()));
        currentDisc = disc;
        currentOutputs = outputs;
        currentName = name;
        currentSubtitle = subtitle;
        currentCover = cover;
        currentIpod = ipod;
        // The engine frees a stopped sound's channel on its next tick and there are only 8 streaming channels:
        // with several speakers the new sounds would find none (a silent track). Wait until the old ones are gone.
        pendingPlay = new ArrayList<>(instances);
        pendingRetiring = retiring;
        pendingWait = 0;
        playPendingWhenFree();
        setExternal(true);
    }

    // The dock this player's own file is playing at, as far as the server was told (so everybody sees the notes)
    @Nullable
    private static GlobalPos externalDock;

    // Tells the dock the list was started from that its owner is (or is no longer) playing a file there
    private static void setExternal(boolean playing) {
        if (playing && queueDock != null && current != null && !paused) {
            if (!queueDock.equals(externalDock)) {
                if (externalDock != null) DockControl.setExternal(externalDock, false);
                DockControl.setExternal(queueDock, true);
                externalDock = queueDock;
            }
        } else if (externalDock != null) {
            DockControl.setExternal(externalDock, false);
            externalDock = null;
        }
    }

    // Ends the current sound but keeps the track list (used when switching tracks)
    private static void endCurrent() {
        setExternal(false);
        refreshPosition();
        saveProgress(); // an audiobook chapter remembers where it was left
        currentProgress = null;
        currentItemKey = null;
        chapterFinished = false;
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            if (current instanceof IpodSoundInstance ours) ours.release();
            current = null;
        }
        for (SoundInstance extra : extras) {
            Minecraft.getInstance().getSoundManager().stop(extra);
            if (extra instanceof IpodSoundInstance ours) ours.release();
        }
        extras = List.of();
        currentOutputs = List.of();
        currentDisc = ItemStack.EMPTY;
        currentName = Component.empty();
        currentSubtitle = Component.empty();
        currentCover = null;
        currentIpod = null;
        paused = false;
        autoPaused = false;
        started = false;
        ticksSinceRefresh = 0;
        ticksSinceSave = 0;
        pendingPlay = List.of(); // a track that never got to start is dropped
        pendingRetiring = List.of();
    }

    // sounds of a new track waiting for the old sounds to give up their audio channels (see start)
    private static List<SoundInstance> pendingPlay = List.of();
    private static List<SoundInstance> pendingRetiring = List.of();
    private static int pendingWait;

    private static void playPendingWhenFree() {
        if (pendingPlay.isEmpty()) return;
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        boolean free = true;
        for (SoundInstance old : pendingRetiring) {
            if (sounds.isActive(old)) free = false;
        }
        if (!free && ++pendingWait <= 20) return; // the 20 ticks are only a safety net
        if (!free) LOGGER.debug("iPod: the old sounds still hold their channels after 20 ticks, starting anyway");
        List<SoundInstance> toPlay = pendingPlay;
        pendingPlay = List.of();
        pendingRetiring = List.of();
        for (SoundInstance instance : toPlay) sounds.play(instance);
    }

    /**
     * The speakers of the dock the list was started from. Empty when the music is not from a dock (or the dock
     * is not there, holds another iPod, or has no usable speaker): then it plays in the player's ears.
     */
    private static List<DockOutputs.Output> outputsFor(Minecraft mc, @Nullable UUID ipod, List<DockOutputs.Output> previous) {
        GlobalPos dockPos = queueDock;
        if (dockPos == null || mc.level == null || !dockPos.dimension().equals(mc.level.dimension())) return List.of();
        if (!(mc.level.getBlockEntity(dockPos.pos()) instanceof DockBlockEntity dock) || !dock.hasIpod()) return List.of();
        if (ipod != null && !ipod.equals(dock.getIpod().get(ModDataComponents.IPOD_ID.get()))) return List.of();
        return DockOutputs.of(mc.level, dock, previous);
    }

    /** Which dock the next list is started from (null for an iPod the player holds). Set by the iPod screen. */
    public static void setQueueDock(@Nullable GlobalPos dock) {
        queueDock = dock;
        queueDockSince = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0;
    }

    // When the list was tied to its dock (game time). The dock needs a moment to tell the client that the iPod is in it.
    private static long queueDockSince = 0;
    private static final long DOCK_GRACE_TICKS = 60;

    /**
     * The iPod this player was playing from has just been put into a dock. A file or chapter carries on, now
     * through the dock's speakers (the outputs check restarts it at the same second once the dock reports its
     * speakers); a music disc is handed to the dock, which plays it for everybody from the start.
     */
    public static void handOverToDock(GlobalPos dock, @Nullable UUID ipod, ItemStack ipodStack) {
        if (current == null || ipod == null || !ipod.equals(currentIpod)) return;
        if (currentDisc.isEmpty()) {
            setQueueDock(dock);
            setExternal(true);
            return;
        }
        List<ItemStack> discs = IpodItem.getDiscs(ipodStack);
        int index = -1;
        for (int i = 0; i < discs.size(); i++) {
            if (ItemStack.isSameItemSameComponents(discs.get(i), currentDisc)) {
                index = i;
                break;
            }
        }
        refreshPosition(); // where the disc is, so it carries on at the same second on the dock
        double seconds = positionSeconds();
        stop();
        if (index >= 0) DockControl.playWhenDocked(dock, index, seconds);
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
        queueDock = null;
    }

    // Freezes the song exactly where it is
    public static void pause() {
        if (current == null) return;
        refreshPosition();
        saveProgress();
        paused = true;
        autoPaused = false;
        setChannelPaused(true);
        setExternal(false);
    }

    // Carries on from exactly where it was frozen
    public static void resume() {
        paused = false;
        autoPaused = false;
        setChannelPaused(false);
        setExternal(true);
    }

    public static void togglePause() {
        if (DockControl.isActive()) {
            DockControl.togglePause();
            return;
        }
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
        lastStep = 1;
        queueIndex = buildOrder(index);
        tracks.get(index).run();
    }

    public static void next() {
        if (DockControl.isActive()) {
            DockControl.next();
            return;
        }
        skip(1);
    }

    public static void previous() {
        if (DockControl.isActive()) {
            DockControl.previous();
            return;
        }
        skip(-1);
    }

    private static void skip(int step) {
        if (queue.isEmpty()) return;
        lastStep = step;
        // floorMod keeps the position inside the list and wraps around at both ends
        queueIndex = Math.floorMod(queueIndex + step, order.length);
        queue.get(order[queueIndex]).run();
    }

    // Pause/Play only works while a song is loaded
    public static boolean canTogglePause() {
        return DockControl.isActive() || isPlaying();
    }

    public static boolean isPaused() {
        return DockControl.isActive() ? DockControl.isPaused() : paused;
    }

    public static void stopIfPlaying(ItemStack removed) {
        if (current != null && ItemStack.isSameItemSameComponents(currentDisc, removed)) {
            stop();
        }
    }

    public static boolean isPlaying() {
        if (DockControl.isActive()) return true; // (paused or not: see isPaused)
        return current != null && Minecraft.getInstance().getSoundManager().isActive(current);
    }

    // True while any track is loaded (even paused or still loading), so the HUD can stay on
    // through Next/Previous transitions. Becomes false only once we stop completely.
    /** True while a track is playing for this player (in their ears or on speakers), not paused. */
    /** Music plays on the player themselves (not through dock speakers), is not paused and not muted. */
    public static boolean playsOnYou() {
        return current != null && !paused && currentOutputs.isEmpty() && !IpodMute.silences(currentIpod);
    }

    public static boolean isListening() {
        return current != null && !paused;
    }

    /** The iPod whose music plays now: the one the player started a song with, or the iPod in the dock that plays a disc. */
    @Nullable
    public static UUID playingIpod() {
        if (current != null) return currentIpod;
        DockBlockEntity dock = DockControl.active();
        return dock != null ? dock.getIpod().get(ModDataComponents.IPOD_ID.get()) : null;
    }

    public static boolean hasTrack() {
        return DockControl.isActive() || !currentName.getString().isEmpty();
    }

    // True if this is the sound the player is currently tracking.
    // A sound that is no longer current has been replaced or stopped, and should end itself.
    public static boolean isCurrent(SoundInstance sound) {
        return sound == current || extras.contains(sound);
    }

    // Closing the Esc menu makes the game resume every sound channel, including a paused song of ours; our own pause
    // from the sound's tick comes up to 50 ms later. So for a few frames after a screen closed, pause it again every frame.
    private static boolean screenWasOpen;
    private static int recheckFrames;

    /** Called every render frame (see ClientEvents). */
    public static void keepPaused() {
        boolean open = Minecraft.getInstance().screen != null;
        if (screenWasOpen && !open) recheckFrames = 4;
        screenWasOpen = open;
        if (recheckFrames > 0) {
            recheckFrames--;
            if (paused && current != null) setChannelPaused(true);
        }
    }

    // Pauses or unpauses the real audio channel that our sound is playing on.
    // This reaches into the sound engine, which is why we needed the access transformer.
    private static void setChannelPaused(boolean pause) {
        if (current == null) return;
        pauseChannel(current, pause);
        for (SoundInstance extra : extras) pauseChannel(extra, pause);
    }

    private static void pauseChannel(SoundInstance sound, boolean pause) {
        SoundEngine engine = Minecraft.getInstance().getSoundManager().soundEngine;
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(sound);
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
        // Music from a dock belongs to the dock, so this only applies to an iPod the player holds.
        boolean hasIpod = queueDock != null || holdsIpod(player);
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

        // Music from a dock stops when the dock is gone, or the iPod was taken out (only if the chunk is loaded)
        if (queueDock != null) {
            if (!queueDock.dimension().equals(mc.level.dimension())) {
                stop();
            } else if (mc.level.hasChunkAt(queueDock.pos())
                    && mc.level.getGameTime() - queueDockSince > DOCK_GRACE_TICKS
                    && !(mc.level.getBlockEntity(queueDock.pos()) instanceof DockBlockEntity dock
                    && dock.hasIpod()
                    && (currentIpod == null || currentIpod.equals(dock.getIpod().get(ModDataComponents.IPOD_ID.get()))))) {
                if (currentIpod != null && findIpod(player, currentIpod) != null) {
                    // the iPod was taken out and is in the player's inventory: carry on in the player's ears,
                    // at the same second (the sounds on the speakers are replaced by one in the ears)
                    setQueueDock(null);
                    restartPending = true; // not here: this runs inside the sound engine's tick, which must not start sounds
                } else {
                    stop();
                }
            }
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