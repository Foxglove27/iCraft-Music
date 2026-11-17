package net.foxglove.icraft.client;

import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.client.audio.AudiobookProgress;
import net.foxglove.icraft.client.share.FileHasher;
import net.foxglove.icraft.client.share.FileUploader;
import net.foxglove.icraft.client.share.LocalFileIndex;
import net.foxglove.icraft.net.DockQueuePayload;
import net.foxglove.icraft.net.share.QueueEntry;
import net.minecraft.Util;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.net.DockControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

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
    // the dock this player last started a disc on
    @Nullable
    private static GlobalPos lastDock;

    // A disc that was playing from a held iPod when it was docked: start it on the dock as soon as the dock has the iPod
    @Nullable
    private static GlobalPos pendingDock;
    private static int pendingDisc;
    private static int pendingTicks;

    /**
     * One track of a list of the player's own files that is shared at a dock: the file, how to show it, the part
     * of the file to play (a chapter: start and end in seconds, end -1 = to the end of the file), and where its
     * saved position lives (the key and the next item's key, as for audiobooks).
     */
    public record FileItem(Path file, Component title, Component album, @Nullable Path cover, double startSeconds,
                           double endSeconds, String progressBook, String progressKey, @Nullable String nextKey) {}

    // The list this player started at a dock: the tracks (a window of the album) and where the window starts
    private record Meta(List<FileItem> items, int offset) {}

    @Nullable
    private static Meta meta;
    private static final int WINDOW_BEFORE = 10;

    // saving the position of audiobook chapters, podcast episodes and songs while the dock plays them
    private static int lastIndex = -1;
    private static double lastSeconds;
    private static double lastLength = Double.MAX_VALUE; // length of the track that was playing at the last tick, in seconds
    private static boolean lastPaused;
    private static int saveTicks;

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
        return dock != null && dock.isFilesMode() && meta != null ? meta.offset() + dock.getPlayback().currentDisc() : -1;
    }

    public static Component currentSubtitle() {
        FileItem item = currentItem();
        return item != null ? item.album() : Component.translatable("screen.icraft.ipod.discs");
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
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.icraft.share.preparing"), true);

        int from = Math.max(0, startIndex - WINDOW_BEFORE);
        int to = Math.min(album.size(), from + DockQueuePayload.MAX_ENTRIES);
        List<FileItem> window = new ArrayList<>(album.subList(from, to));
        int startInWindow = startIndex - from;
        boolean shuffle = !book && IpodSettings.shuffle();
        int repeat = book ? 0 : IpodSettings.repeat().ordinal();

        CompletableFuture.supplyAsync(() -> {
            List<QueueEntry> entries = new ArrayList<>();
            List<FileUploader.Item> uploads = new ArrayList<>();
            for (FileItem item : window) {
                FileHasher.Info info;
                try {
                    info = FileHasher.of(item.file());
                } catch (IOException e) {
                    return null; // a file cannot be read: do not start a list that has a hole in it
                }
                String name = item.file().getFileName().toString();
                String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                double length = info.lengthSeconds() > 0 ? info.lengthSeconds() : info.size() * 8.0 / 128_000; // unknown: guess 128 kbit/s
                Optional<QueueEntry> entry = QueueEntry.validated(info.hash(), ext, item.title().getString(),
                        item.album().getString(), (int) Math.round(item.startSeconds() * 20),
                        item.endSeconds() >= 0 ? (int) Math.round(item.endSeconds() * 20) : -1, (int) Math.round(length * 20));
                if (entry.isEmpty()) return null;
                entries.add(entry.get());
                uploads.add(new FileUploader.Item(info.hash(), item.file(), ext, info.size(), item.title().getString()));
            }
            return new Object[]{entries, uploads};
        }, Util.backgroundExecutor()).thenAccept(result -> mc.execute(() -> {
            if (result == null) {
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.icraft.share.unreadable"), true);
                return;
            }
            @SuppressWarnings("unchecked") List<QueueEntry> entries = (List<QueueEntry>) result[0];
            @SuppressWarnings("unchecked") List<FileUploader.Item> uploads = (List<FileUploader.Item>) result[1];
            for (int i = 0; i < window.size(); i++) LocalFileIndex.put(entries.get(i).hash(), window.get(i).file());
            FileUploader.reset(uploads);
            meta = new Meta(window, from);
            lastIndex = -1;
            lastDock = dock;
            FileItem first = window.get(startInWindow);
            double saved = AudiobookProgress.get(first.progressBook(), first.progressKey());
            int offsetTicks = (int) Math.round(Math.max(0, saved - 2.0) * 20); // a little earlier than where it was left
            PacketDistributor.sendToServer(new DockQueuePayload(dock.pos(), startInWindow, offsetTicks, shuffle, repeat, entries));
        }));
    }

    // ---- keeping the position of the track that plays (audiobooks, podcasts, songs) ----

    private static void saveOrFinish(int index, double seconds, double length) {
        Meta m = meta;
        if (m == null || index < 0 || index >= m.items().size()) return;
        FileItem item = m.items().get(index);
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

        DockPlayback pb = dock.getPlayback();
        int index = pb.currentDisc();
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
    public static void playWhenDocked(GlobalPos dock, int index) {
        pendingDock = dock;
        pendingDisc = index;
        pendingTicks = 60;
    }

    /** Called every client tick. */
    static void tick() {
        tickProgress();
        if (pendingDock == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null && pendingDock.dimension().equals(mc.level.dimension())
                && mc.level.getBlockEntity(pendingDock.pos()) instanceof DockBlockEntity dock
                && dock.hasIpod() && dock.isOwner(mc.player)) {
            GlobalPos target = pendingDock;
            pendingDock = null;
            play(target, pendingDisc);
        } else if (--pendingTicks <= 0) {
            pendingDock = null;
        }
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
        return dock != null && dock.getPlayback().isPaused();
    }

    /** The index (in the iPod's disc list) of the disc that is playing, or -1. */
    public static int currentDisc() {
        DockBlockEntity dock = active();
        return dock != null ? dock.getPlayback().currentDisc() : -1;
    }

    /** Where the current disc is, in seconds (for skipping). */
    public static ItemStack currentDiscStack() {
        DockBlockEntity dock = active();
        if (dock == null) return ItemStack.EMPTY;
        var discs = IpodItem.getDiscs(dock.getIpod());
        int i = dock.getPlayback().currentDisc();
        return i >= 0 && i < discs.size() ? discs.get(i) : ItemStack.EMPTY;
    }

    public static Component currentName() {
        FileItem item = currentItem();
        if (item != null) return item.title();
        ItemStack disc = currentDiscStack();
        Minecraft mc = Minecraft.getInstance();
        if (disc.isEmpty() || mc.level == null) return Component.empty();
        return JukeboxSong.fromStack(mc.level.registryAccess(), disc)
                .map(h -> h.value().description()).orElse(disc.getHoverName());
    }

    // ---- sending ----

    private static void send(GlobalPos dock, int action, int arg) {
        PacketDistributor.sendToServer(new DockControlPayload(dock.pos(), action, arg,
                IpodSettings.shuffle(), IpodSettings.repeat().ordinal()));
    }

    /** Starts the docked iPod's disc list at disc {@code index}. Stops anything this player plays locally. */
    public static void play(GlobalPos dock, int index) {
        IpodPlayer.stop(); // a file or disc playing in the player's ears makes way
        lastDock = dock;
        send(dock, DockControlPayload.PLAY, index);
    }

    public static void togglePause() {
        DockBlockEntity dock = active();
        if (dock == null) return;
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
        sendIfActive(DockControlPayload.NEXT, 0);
    }

    public static void previous() {
        sendIfActive(DockControlPayload.PREVIOUS, 0);
    }

    public static void stop() {
        sendIfActive(DockControlPayload.STOP, 0);
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
        send(GlobalPos.of(Minecraft.getInstance().level.dimension(), dock.getBlockPos()), action, arg);
    }

    /** Tells a dock that the owner plays one of their own files there (so everybody sees the notes). */
    public static void setExternal(GlobalPos dock, boolean playing) {
        PacketDistributor.sendToServer(new DockControlPayload(dock.pos(), DockControlPayload.EXTERNAL,
                playing ? 1 : 0, false, 0));
    }
}
