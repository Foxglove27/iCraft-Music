package net.foxglove.icraft.client;

import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.client.audio.AudiobookProgress;
import net.foxglove.icraft.client.share.FileHasher;
import net.foxglove.icraft.client.share.FileUploader;
import net.foxglove.icraft.client.share.LocalFileIndex;
import net.foxglove.icraft.client.share.SavedAlbums;
import net.foxglove.icraft.net.DockQueuePayload;
import net.foxglove.icraft.net.share.QueueEntry;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.net.DockControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The owner's side of a dock that plays music discs: sends the control messages and tells the rest of the
 * client (screen, HUD, keys) what the dock is playing. The server does the playing; see {@link DockClientPlayer}.
 */
public final class DockControl {
    private static final Logger LOGGER = LogUtils.getLogger();

    // the dock this player last started a disc on
    @Nullable
    private static GlobalPos lastDock;

    // A disc that was playing from a held iPod when it was docked: start it on the dock as soon as the dock has the iPod
    @Nullable
    private static GlobalPos pendingDock;
    private static int pendingDisc;
    private static double pendingSeconds;
    private static int pendingTicks;

    /**
     * One track of a list of the player's own files that is shared at a dock: the file, how to show it, the part
     * of the file to play (a chapter: start and end in seconds, end -1 = to the end of the file), and where its
     * saved position lives (the key and the next item's key, as for audiobooks).
     */
    public record FileItem(Path file, Component title, Component album, @Nullable Path cover, double startSeconds,
                           double endSeconds, @Nullable String progressBook, String progressKey, @Nullable String nextKey) {}

    // The list this player started at a dock: the tracks (a window of the album) and where the window starts
    // 'album' is the whole list the player picked from, 'items' only the part (window) the server holds
    private record Meta(List<FileItem> items, int offset, List<FileItem> album, boolean book) {
        /** True if the server's queue limit cut the album: the client then moves the window itself. */
        boolean partial() {
            return album.size() > items.size();
        }
    }

    @Nullable
    private static Meta meta;
    private static final int WINDOW_BEFORE = 10;

    // saving the position of audiobook chapters, podcast episodes and songs while the dock plays them
    private static int lastIndex = -1;
    private static double lastSeconds;
    private static double lastLength = Double.MAX_VALUE; // length of the track that was playing at the last tick, in seconds
    private static boolean lastPaused;
    private static int saveTicks;

    // The album tracks after the window that were last sent to FileUploader for uploading ahead of time (see prefetchAhead)
    @Nullable
    private static Meta prefetchMeta;
    private static int prefetchKey = -1;

    // A partial list that was seen playing: when the server stops it (end of the window), the next album track follows
    private static boolean slideArmed;

    private DockControl() {}

    /** True if the server shares files, so a list of own files can be played through the dock for everybody. */
    public static boolean canShareFiles() {
        return ServerConfig.sharingEnabled();
    }

    /** The track of the shared list that is playing, or null. */
    @Nullable
    private static FileItem currentItem() {
        DockBlockEntity dock = active();
        if (dock == null || !dock.isFilesMode() || meta == null) return null;
        assert dock.getPlayback() != null;
        int i = dock.getPlayback().currentDisc();
        return i >= 0 && i < meta.items().size() ? meta.items().get(i) : null;
    }

    /** True while the dock plays a list of this player's own files. */
    public static boolean isFilesActive() {
        DockBlockEntity dock = active();
        return dock != null && dock.isFilesMode();
    }

    /** Which track of the album (the list the player started from) is playing, or -1. */
    public static int currentIndexInAlbum() {
        DockBlockEntity dock = active();
        if (dock != null && dock.isFilesMode() && meta != null) {
            assert dock.getPlayback() != null;
            return meta.offset() + dock.getPlayback().currentDisc();
        } else {
            return -1;
        }
    }

    /**
     * How far into its own track the item with this progress key is, while the dock plays it from the owner's list,
     * or -1 if that item is not the one playing. For the live time on the row (the server clock gives the position).
     */
    public static double itemSeconds(String itemKey) {
        FileItem item = currentItem();
        DockBlockEntity dock = active();
        if (item == null || dock == null || !item.progressKey().equals(itemKey)) return -1;
        assert dock.getPlayback() != null;
        assert Minecraft.getInstance().level != null;
        return dock.getPlayback().positionTicks(Minecraft.getInstance().level.getGameTime()) / 20.0;
    }

    public static Component currentSubtitle() {
        FileItem item = currentItem();
        return item != null ? item.album() : Component.translatable("screen.icraft_music.ipod.discs");
    }

    @Nullable
    public static Path currentCover() {
        FileItem item = currentItem();
        return item != null ? item.cover() : null;
    }

    /**
     * Plays a list of the player's own files through the dock for everybody (file sharing). The files are hashed
     * in the background; then the list goes to the server and the files are uploaded as they are needed.
     */
    public static void playFiles(GlobalPos dock, List<FileItem> album, int startIndex, boolean book) {
        if (album.isEmpty() || startIndex < 0 || startIndex >= album.size()) return;
        IpodPlayer.stop();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.icraft_music.share.preparing"), true);

        // the server keeps at most maxQueueItems tracks: size the window so the clicked track is always inside it
        int limit = Math.clamp(ServerConfig.MAX_QUEUE_ITEMS.get(), 1, DockQueuePayload.MAX_ENTRIES);
        int from = Math.max(0, startIndex - Math.min(WINDOW_BEFORE, limit - 1));
        int to = Math.min(album.size(), from + limit);
        List<FileItem> window = new ArrayList<>(album.subList(from, to));
        int startInWindow = startIndex - from;
        boolean partial = album.size() > window.size();
        // the server only sees the window, so for a partial one shuffle and list repeat are done here (see slideTarget)
        boolean shuffle = !book && !partial && IpodSettings.shuffle();
        int repeat = book ? 0 : serverRepeat(partial).ordinal();

        CompletableFuture.<Object>supplyAsync(() -> {
            // returns the entries and uploads, or the name of the file that could not be used (a String)
            try {
                List<QueueEntry> entries = new ArrayList<>();
                List<FileUploader.Item> uploads = new ArrayList<>();
                long lastProgress = System.currentTimeMillis();
                int done = 0;
                for (FileItem item : window) {
                    done++;
                    long nowMs = System.currentTimeMillis();
                    if (window.size() > 3 && nowMs - lastProgress > 1000) { // hashing a big list from a network share takes a while
                        lastProgress = nowMs;
                        int shown = done;
                        mc.execute(() -> {
                            if (mc.player != null) mc.player.displayClientMessage(Component.translatable(
                                    "message.icraft_music.share.preparing_n", shown, window.size()), true);
                        });
                    }
                    FileHasher.Info info;
                    try {
                        info = FileHasher.of(item.file());
                    } catch (IOException e) {
                        return item.file().getFileName().toString(); // a file cannot be read: do not start a list that has a hole in it
                    }
                    String name = item.file().getFileName().toString();
                    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                    double length = info.lengthSeconds() > 0 ? info.lengthSeconds() : info.size() * 8.0 / 128_000; // unknown: guess 128 kbit/s
                    Optional<QueueEntry> entry = QueueEntry.validated(info.hash(), ext, item.title().getString(),
                            net.foxglove.icraft.client.audio.LocalMusic.shareAlbumName(item.file()), // only the folder's own name goes out
                            (int) Math.round(item.startSeconds() * 20),
                            item.endSeconds() >= 0 ? (int) Math.round(item.endSeconds() * 20) : -1, (int) Math.round(length * 20));
                    if (entry.isEmpty()) return item.file().getFileName().toString();
                    entries.add(entry.get());
                    uploads.add(new FileUploader.Item(info.hash(), item.file(), ext, info.size(), item.title().getString()));
                }
                return new Object[]{entries, uploads};
            } finally {
                FileHasher.flush(); // remember the new hashes on disk
            }
        }, Util.backgroundExecutor()).thenAccept(result -> mc.execute(() -> {
            if (result instanceof String badFile) {
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.icraft_music.share.unreadable", badFile), true);
                return;
            }
            @SuppressWarnings("unchecked") List<QueueEntry> entries = (List<QueueEntry>) ((Object[]) result)[0];
            @SuppressWarnings("unchecked") List<FileUploader.Item> uploads = (List<FileUploader.Item>) ((Object[]) result)[1];
            for (int i = 0; i < window.size(); i++) LocalFileIndex.put(entries.get(i).hash(), window.get(i).file());
            FileUploader.reset(uploads);
            meta = new Meta(window, from, album, book);
            slideArmed = false;
            prefetchMeta = null;
            rememberAlbum(dock, meta);
            lastIndex = -1;
            lastDock = dock;
            FileItem first = window.get(startInWindow);
            double saved = first.progressBook() == null ? 0 : AudiobookProgress.get(first.progressBook(), first.progressKey()); // songs are not resumed
            int offsetTicks = (int) Math.round(Math.max(0, saved - 2.0) * 20); // a little earlier than where it was left
            net.foxglove.icraft.client.ClientNet.send(new DockQueuePayload(dock.pos(), startInWindow, offsetTicks, shuffle, repeat, entries));
        }));
    }

    // ---- keeping the position of the track that plays (audiobooks, podcasts, songs) ----

    private static void saveOrFinish(int index, double seconds, double length) {
        Meta m = meta;
        if (m == null || index < 0 || index >= m.items().size()) return;
        FileItem item = m.items().get(index);
        if (item.progressBook() == null) return; // songs keep no position
        if (seconds >= length - 3.0) { // played to the end
            AudiobookProgress.finish(item.progressBook(), item.progressKey(), item.nextKey());
        } else if (seconds >= 1.0) {
            AudiobookProgress.save(item.progressBook(), item.progressKey(), seconds);
        }
    }

    // Called every client tick: uploads for the owner, and keeps the saved position of the playing track
    private static void tickProgress() {
        DockBlockEntity dock = active();
        if (dock == null || !dock.isFilesMode() || meta == null) {
            if (lastIndex >= 0) { // it stopped: keep where it was
                saveOrFinish(lastIndex, lastSeconds, lastLength);
                lastIndex = -1;
            }
            return;
        }
        FileUploader.tick(dock);
        if (meta.partial()) {
            slideArmed = true; // seen playing: when the server stops it, the album goes on
            prefetchAhead(meta);
        }

        DockPlayback pb = dock.getPlayback();
        assert pb != null;
        int index = pb.currentDisc();
        assert Minecraft.getInstance().level != null;
        double seconds = pb.positionTicks(Minecraft.getInstance().level.getGameTime()) / 20.0;
        if (index != lastIndex) {
            if (lastIndex >= 0) saveOrFinish(lastIndex, lastSeconds, lastLength);
            lastIndex = index;
            saveTicks = 0;
        }
        List<QueueEntry> queue = dock.getQueue();
        double length = index < queue.size() ? queue.get(index).playTicks() / 20.0 : Double.MAX_VALUE;
        lastSeconds = seconds;
        lastLength = length;
        if (pb.isPaused() && !lastPaused) saveOrFinish(index, seconds, length);
        lastPaused = pb.isPaused();
        if (!pb.isPaused() && ++saveTicks >= 200) { // every 10 seconds
            saveTicks = 0;
            saveOrFinish(index, seconds, length);
        }
    }

    /** Starts disc {@code index} on the dock once it has reported that it holds the iPod (a few ticks). */
    public static void playWhenDocked(GlobalPos dock, int index, double seconds) {
        pendingDock = dock;
        pendingDisc = index;
        pendingSeconds = seconds;
        pendingTicks = 60;
    }

    /** Called every client tick. */
    static void tick() {
        tickProgress();
        tickSlide();
        if (pendingDock == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null && pendingDock.dimension().equals(mc.level.dimension())
                && mc.level.getBlockEntity(pendingDock.pos()) instanceof DockBlockEntity dock
                && dock.hasIpod() && dock.isOwner(mc.player)) {
            GlobalPos target = pendingDock;
            pendingDock = null;
            play(target, pendingDisc, pendingSeconds);
        } else if (--pendingTicks <= 0) {
            pendingDock = null;
        }
    }

    /**
     * A dock the player owns is playing but this client did not start it (the player logged out and back in, which
     * forgets {@link #lastDock}): remember it so pause, skip and the HUD work again. Never replaces a known dock.
     */
    public static void adopt(GlobalPos dock, DockBlockEntity entity) {
        if (lastDock != null) return;
        lastDock = dock;
        if (meta != null) { // the album of the list this player started before the relog: only if it is this dock's list
            boolean same = metaMatches(entity);
            LOGGER.info("Dock at {}: adopted after joining; the album of the earlier list was {}", dock.pos().toShortString(),
                    same ? "kept" : "dropped");
            if (!same) meta = null;
        } else {
            restoreAlbum(dock, entity);
        }
    }

    /** Key of a dock in the saved albums file. */
    private static String albumKey(GlobalPos dock) {
        return dock.dimension().location() + "|" + dock.pos().getX() + "," + dock.pos().getY() + "," + dock.pos().getZ();
    }

    /** Saves the album of a long list (or forgets it for a list that fits), so skipping survives a game restart. */
    private static void rememberAlbum(GlobalPos dock, Meta m) {
        String key = albumKey(dock);
        Util.backgroundExecutor().execute(() -> {
            if (m.partial()) SavedAlbums.save(key, m.offset(), m.book(), m.album());
            else SavedAlbums.remove(key);
        });
    }

    /**
     * After a game restart: the dock plays a list of this player's files but the album is unknown. If a saved album
     * exists whose window is exactly the dock's queue (checked through the files' hashes), take it back; else forget it.
     */
    private static void restoreAlbum(GlobalPos dock, DockBlockEntity entity) {
        if (!entity.isFilesMode() || entity.getQueue().isEmpty()) return;
        String key = albumKey(dock);
        List<QueueEntry> queue = List.copyOf(entity.getQueue());
        Minecraft mc = Minecraft.getInstance();
        Util.backgroundExecutor().execute(() -> {
            SavedAlbums.Album saved = SavedAlbums.get(key);
            if (saved == null) return;
            boolean ok = saved.offset() >= 0 && saved.offset() + queue.size() <= saved.items().size()
                    && saved.items().size() > queue.size();
            try {
                for (int i = 0; ok && i < queue.size(); i++) {
                    ok = FileHasher.of(saved.items().get(saved.offset() + i).file()).hash().equals(queue.get(i).hash());
                }
            } catch (IOException e) {
                ok = false; // a file is gone or unreadable
            } finally {
                FileHasher.flush();
            }
            boolean matches = ok;
            if (!matches) SavedAlbums.remove(key);
            mc.execute(() -> {
                if (!matches) {
                    LOGGER.info("Dock at {}: the saved album no longer matches and was dropped", dock.pos().toShortString());
                    return;
                }
                if (meta != null || !dock.equals(lastDock)) return; // something else happened meanwhile
                List<FileItem> window = new ArrayList<>(saved.items().subList(saved.offset(), saved.offset() + queue.size()));
                for (int i = 0; i < window.size(); i++) LocalFileIndex.put(queue.get(i).hash(), window.get(i).file());
                meta = new Meta(window, saved.offset(), saved.items(), saved.book());
                LOGGER.info("Dock at {}: the album of the list was restored from the saved file", dock.pos().toShortString());
            });
        });
    }

    /**
     * While a long list plays, hashes the next album tracks that lie after the server's window and hands them to
     * {@link FileUploader} to upload quietly ahead of time, so a slide to them does not wait. Not with shuffle (the next
     * track is random then).
     */
    private static void prefetchAhead(Meta m) {
        boolean shuffled = !m.book() && IpodSettings.shuffle();
        int cur = currentIndexInAlbum();
        if (cur < 0) return;
        int key = cur * 8 + (shuffled ? 1 : 0) + 2 * IpodSettings.repeat().ordinal();
        if (m == prefetchMeta && key == prefetchKey) return;
        prefetchMeta = m;
        prefetchKey = key;
        if (shuffled) return;
        int n = m.album().size();
        List<FileItem> next = new ArrayList<>();
        for (int k = 1; k <= 2; k++) {
            int t = cur + k;
            if (t >= n) {
                if (m.book() || IpodSettings.repeat() != RepeatMode.LIST) break;
                t = Math.floorMod(t, n);
            }
            if (t < m.offset() || t >= m.offset() + m.items().size()) next.add(m.album().get(t)); // inside the window the queue lookahead does it
        }
        if (next.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Util.backgroundExecutor().execute(() -> {
            List<FileUploader.Item> uploads = new ArrayList<>();
            try {
                for (FileItem item : next) {
                    FileHasher.Info info = FileHasher.of(item.file());
                    String name = item.file().getFileName().toString();
                    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                    if (!QueueEntry.EXTENSIONS.contains(ext)) continue;
                    uploads.add(new FileUploader.Item(info.hash(), item.file(), ext, info.size(), item.title().getString()));
                }
            } catch (IOException e) {
                return; // not readable now: the slide itself reports it
            } finally {
                FileHasher.flush();
            }
            mc.execute(() -> {
                if (meta == m && prefetchMeta == m && prefetchKey == key) FileUploader.addPrefetch(uploads); // the list may have changed meanwhile
            });
        });
    }

    /** True if the dock's queue is exactly the window of {@link #meta} (same files, found through their hashes). */
    private static boolean metaMatches(DockBlockEntity dock) {
        Meta m = meta;
        if (m == null || !dock.isFilesMode()) return false;
        List<QueueEntry> queue = dock.getQueue();
        if (queue.size() != m.items().size()) return false;
        for (int i = 0; i < queue.size(); i++) {
            Path file = LocalFileIndex.get(queue.get(i).hash());
            if (file == null || !file.equals(m.items().get(i).file())) return false;
        }
        return true;
    }

    /** The player left a world (or joined one): forget docks and pending actions of the old one (the album stays, see adopt). */
    public static void forgetWorld() {
        // 'meta' stays: after a relog the owner can go on with the same album; adopt() checks it belongs to the dock
        lastIndex = -1;
        lastDock = null;
        pendingDock = null;
        slideArmed = false;
        prefetchMeta = null;
    }

    // ---- what is going on ----

    /** The dock whose disc list this player is playing right now, or null. */
    @Nullable
    public static DockBlockEntity active() {
        Minecraft mc = Minecraft.getInstance();
        if (lastDock == null || mc.level == null || mc.player == null || !lastDock.dimension().equals(mc.level.dimension())) return null;
        if (!(mc.level.getBlockEntity(lastDock.pos()) instanceof DockBlockEntity dock)) return null;
        DockPlayback pb = dock.getPlayback();
        return dock.hasIpod() && dock.isOwner(mc.player) && pb != null && pb.isPlaying() ? dock : null;
    }

    public static boolean isActive() {
        return active() != null;
    }

    public static boolean isPaused() {
        DockBlockEntity dock = active();
        if (dock == null) return false;
        assert dock.getPlayback() != null;
        return dock.getPlayback().isPaused();
    }

    /** The index (in the iPod's disc list) of the disc that is playing, or -1. */
    public static int currentDisc() {
        DockBlockEntity dock = active();
        if (dock != null) {
            assert dock.getPlayback() != null;
            return dock.getPlayback().currentDisc();
        } else {
            return -1;
        }
    }

    /** Where the current disc is, in seconds (for skipping). */
    public static ItemStack currentDiscStack() {
        DockBlockEntity dock = active();
        if (dock == null) return ItemStack.EMPTY;
        var discs = IpodItem.getDiscs(dock.getIpod());
        assert dock.getPlayback() != null;
        int i = dock.getPlayback().currentDisc();
        return i >= 0 && i < discs.size() ? discs.get(i) : ItemStack.EMPTY;
    }

    public static Component currentName() {
        FileItem item = currentItem();
        if (item != null) return item.title();
        return discName(currentDiscStack());
    }

    /** The song name of a music disc (its jukebox description), or the item's name; empty for no disc. */
    public static Component discName(ItemStack disc) {
        Minecraft mc = Minecraft.getInstance();
        if (disc.isEmpty() || mc.level == null) return Component.empty();
        return JukeboxSong.fromStack(mc.level.registryAccess(), disc)
                .map(h -> h.value().description()).orElse(disc.getHoverName());
    }

    // ---- sending ----

    private static void send(GlobalPos dock, int action, int arg) {
        boolean partial = isFilesActive() && meta != null && meta.partial();
        net.foxglove.icraft.client.ClientNet.send(new DockControlPayload(dock.pos(), action, arg,
                !partial && IpodSettings.shuffle(), serverRepeat(partial).ordinal(), 0));
    }

    /** The repeat mode the server gets: for a partial list it must not wrap inside the window, so LIST becomes OFF. */
    private static RepeatMode serverRepeat(boolean partial) {
        RepeatMode mode = IpodSettings.repeat();
        return partial && mode == RepeatMode.LIST ? RepeatMode.OFF : mode;
    }

    /** Starts the docked iPod's disc list at disc {@code index}. Stops anything this player plays locally. */
    public static void play(GlobalPos dock, int index) {
        play(dock, index, 0);
    }

    /** Same, beginning {@code seconds} into the disc (a disc that was playing on a held iPod and was docked). */
    public static void play(GlobalPos dock, int index, double seconds) {
        slideArmed = false;
        IpodPlayer.stop(); // a file or disc playing in the player's ears makes way
        lastDock = dock;
        net.foxglove.icraft.client.ClientNet.send(new DockControlPayload(dock.pos(), DockControlPayload.PLAY, index,
                IpodSettings.shuffle(), IpodSettings.repeat().ordinal(), (int) Math.round(seconds * 20)));
    }

    public static void togglePause() {
        DockBlockEntity dock = active();
        if (dock == null) return;
        assert Minecraft.getInstance().level != null;
        assert dock.getPlayback() != null;
        send(GlobalPos.of(Minecraft.getInstance().level.dimension(), dock.getBlockPos()),
                dock.getPlayback().isPaused() ? DockControlPayload.RESUME : DockControlPayload.PAUSE, 0);
    }

    /** The real pause or resume of a dock the player owns (everybody hears it). */
    public static void togglePauseAt(DockBlockEntity dock) {
        DockPlayback pb = dock.getPlayback();
        if (pb == null || Minecraft.getInstance().level == null) return;
        lastDock = GlobalPos.of(Minecraft.getInstance().level.dimension(), dock.getBlockPos());
        send(lastDock, pb.isPaused() ? DockControlPayload.RESUME : DockControlPayload.PAUSE, 0);
    }

    public static void next() {
        if (slideSkip(1)) return;
        sendIfActive(DockControlPayload.NEXT, 0);
    }

    public static void previous() {
        if (slideSkip(-1)) return;
        sendIfActive(DockControlPayload.PREVIOUS, 0);
    }

    public static void stop() {
        slideArmed = false; // we stop it ourselves: the album must not go on
        sendIfActive(DockControlPayload.STOP, 0);
    }

    // ---- a list longer than the server's queue limit ----

    /**
     * Next/Previous on a partial list: the server only knows the window, so at its edge (or with shuffle) the
     * client sends a new window around the target track. Returns false when the server can handle the skip.
     */
    private static boolean slideSkip(int step) {
        Meta m = meta;
        DockBlockEntity dock = active();
        if (m == null || dock == null || !dock.isFilesMode() || !m.partial()) return false;
        int cur = currentIndexInAlbum();
        if (cur < 0) return false;
        int local = cur - m.offset();
        boolean shuffled = !m.book() && IpodSettings.shuffle();
        boolean edge = step > 0 ? local >= m.items().size() - 1 : local <= 0;
        if (!shuffled && !edge) return false;
        slideTo(m, slideTarget(m, cur, step, false));
        return true;
    }

    /**
     * The album track that follows {@code cur}: a random other one with shuffle, else the neighbour (wrapping). When
     * a track ended by itself ({@code ended}), the album stops after the last track unless repeat is LIST; -1 = stop.
     */
    private static int slideTarget(Meta m, int cur, int step, boolean ended) {
        int n = m.album().size();
        if (!m.book() && IpodSettings.shuffle() && n > 1) {
            int pick = java.util.concurrent.ThreadLocalRandom.current().nextInt(n - 1);
            return pick >= cur ? pick + 1 : pick;
        }
        int target = cur + step;
        if (ended && target >= n && (m.book() || IpodSettings.repeat() != RepeatMode.LIST)) return -1;
        return Math.floorMod(target, n);
    }

    /** Starts a new window of the album at {@code target}; the position of the track that is left is saved first. */
    private static void slideTo(Meta m, int target) {
        if (target < 0 || lastDock == null) return;
        if (lastIndex >= 0) {
            saveOrFinish(lastIndex, lastSeconds, lastLength);
            lastIndex = -1;
        }
        slideArmed = false;
        LOGGER.info("Dock at {}: sliding the list to album track {} of {}", lastDock.pos().toShortString(), target + 1, m.album().size());
        playFiles(lastDock, m.album(), target, m.book());
    }

    /** The server stopped a partial list (its window ended, or a hold-skip went past the end): go on in the album. */
    private static void tickSlide() {
        Minecraft mc = Minecraft.getInstance();
        Meta m = meta;
        if (!slideArmed || m == null || lastDock == null || mc.level == null || mc.player == null
                || !lastDock.dimension().equals(mc.level.dimension())) return;
        if (!(mc.level.getBlockEntity(lastDock.pos()) instanceof DockBlockEntity dock)) return;
        DockPlayback pb = dock.getPlayback();
        if (!dock.hasIpod() || !dock.isOwner(mc.player) || !dock.isFilesMode() || pb == null || pb.isPlaying()) return;
        slideArmed = false;
        slideTo(m, slideTarget(m, m.offset() + pb.currentDisc(), 1, true));
    }

    /** Skips within the disc by this many seconds (ignored while paused; the server clamps it). */
    public static void skipSeconds(double seconds) {
        sendIfActive(DockControlPayload.SEEK, (int) Math.round(seconds * 20));
    }

    /** Shuffle or repeat were changed in the settings. */
    public static void modesChanged() {
        sendIfActive(DockControlPayload.MODES, 0);
    }

    private static void sendIfActive(int action, int arg) {
        DockBlockEntity dock = active();
        if (dock == null) return;
        assert Minecraft.getInstance().level != null;
        send(GlobalPos.of(Minecraft.getInstance().level.dimension(), dock.getBlockPos()), action, arg);
    }

    /** Tells a dock that the owner plays one of their own files there (so everybody sees the notes). */
    public static void setExternal(GlobalPos dock, boolean playing) {
        net.foxglove.icraft.client.ClientNet.send(new DockControlPayload(dock.pos(), DockControlPayload.EXTERNAL,
                playing ? 1 : 0, false, 0, 0));
    }
}
