package net.foxglove.icraft.client.share;

import com.mojang.logging.LogUtils;
import net.foxglove.icraft.ClientConfig;
import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.net.FileDataPayload;
import net.foxglove.icraft.net.FileRequestPayload;
import net.foxglove.icraft.net.FileStatusPayload;
import net.foxglove.icraft.net.share.ChunkAssembler;
import net.foxglove.icraft.net.share.QueueEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The listener's side of file sharing: downloads the files the docks near the player play (the one that plays
 * now and the next one) into a small cache folder in the game directory. One download at a time.
 */
public final class SharedFiles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_CACHE_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 4096L * 1024 * 1024;
    private static final long STALL_MS = 20_000;
    private static final long RETRY_MS = 3_000;
    private static final long REFUSED_MS = 60_000;

    private static final class Download {
        final QueueEntry entry;
        ChunkAssembler assembler;
        long total = -1;
        long lastData = System.currentTimeMillis();
        long lastMessage;

        Download(QueueEntry entry) {
            this.entry = entry;
        }
    }

    private static final Map<Long, List<QueueEntry>> WANTED = new java.util.LinkedHashMap<>(); // per dock
    private static Download active;
    private static final Map<String, Long> RETRY_AT = new HashMap<>();
    private static final Map<String, Long> REFUSED_UNTIL = new HashMap<>();

    private SharedFiles() {}

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("icraft_music-shared");
    }

    /** Where to find the file of an entry on this computer, or null if it is not here (yet). */
    @Nullable
    public static Path pathFor(QueueEntry entry) {
        if (!QueueEntry.isSafe(entry.hash(), entry.ext())) return null; // no file name from an unchecked value
        Path own = LocalFileIndex.get(entry.hash());
        if (own != null) return own;
        Path cached = dir().resolve(entry.hash() + "." + entry.ext());
        if (Files.isRegularFile(cached)) {
            try {
                Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
            }
            return cached;
        }
        return null;
    }

    /** The files this player would like to have now, most important first (called every tick near a playing dock). */
    public static void want(long dockKey, List<QueueEntry> entries) {
        if (entries.isEmpty()) WANTED.remove(dockKey);
        else WANTED.put(dockKey, entries);
    }

    private static List<QueueEntry> allWanted() {
        List<QueueEntry> all = new ArrayList<>();
        for (List<QueueEntry> l : WANTED.values()) all.addAll(l);
        return all;
    }

    private static boolean enabled() {
        return ServerConfig.sharingEnabled() && ClientConfig.DOWNLOAD_SHARED_FILES.get();
    }

    private static void message(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(text, true);
    }

    /** Forgets everything (leaving a world, a lost connection): no download, no wanted files. */
    public static void reset() {
        cancel();
        WANTED.clear();
        RETRY_AT.clear();
        REFUSED_UNTIL.clear();
    }

    /** Every client tick. */
    public static void tick() {
        if (!net.foxglove.icraft.client.ClientNet.connected()) { // left the world, or the server stopped: nothing to ask for
            if (active != null || !WANTED.isEmpty()) reset();
            return;
        }
        if (!enabled()) {
            cancel();
            return;
        }
        long now = System.currentTimeMillis();
        if (active != null) {
            if (now - active.lastData > STALL_MS) { // nothing came for a while: try again later
                cancel();
                return;
            }
            return;
        }
        for (QueueEntry e : allWanted()) {
            if (pathFor(e) != null) continue;
            if (RETRY_AT.getOrDefault(e.hash(), 0L) > now || REFUSED_UNTIL.getOrDefault(e.hash(), 0L) > now) continue;
            start(e);
            return;
        }
    }

    private static void start(QueueEntry entry) {
        trimCache(allWanted().stream().map(QueueEntry::hash).collect(Collectors.toSet()));
        LOGGER.info("Shared files: asking the server for {} ({})", entry.hash().substring(0, 12), entry.title());
        if (!net.foxglove.icraft.client.ClientNet.send(new FileRequestPayload(entry.hash(), 0))) return; // no connection
        active = new Download(entry);
    }

    private static void cancel() {
        if (active == null) return;
        try {
            if (active.assembler != null) {
                active.assembler.close();
                Files.deleteIfExists(active.assembler.partFile());
            }
        } catch (IOException ignored) {
        }
        RETRY_AT.put(active.entry.hash(), System.currentTimeMillis() + RETRY_MS);
        active = null;
    }

    /** The server answered a request: not available yet, or refused. */
    public static boolean onStatus(FileStatusPayload status) {
        if (active == null || !active.entry.hash().equals(status.hash())) return false;
        QueueEntry entry = active.entry;
        cancel();
        if (status.status() == FileStatusPayload.REFUSED) {
            REFUSED_UNTIL.put(entry.hash(), System.currentTimeMillis() + REFUSED_MS);
            LOGGER.info("Shared files: the server refused {}: {}", entry.hash().substring(0, 12), status.reason());
            if (!"not_here".equals(status.reason())) {
                message(Component.translatable("message.icraft_music.share.refused." + status.reason(), entry.title()));
            }
        }
        return true;
    }

    /** A piece of the file arrived. */
    public static void onData(FileDataPayload data) {
        if (active == null || !active.entry.hash().equals(data.hash())) return;
        try {
            if (active.assembler == null) {
                if (data.offset() != 0 || data.total() <= 0 || data.total() > MAX_FILE_BYTES) {
                    cancel();
                    return;
                }
                Files.createDirectories(dir());
                active.total = data.total();
                active.assembler = new ChunkAssembler(dir().resolve(active.entry.hash() + ".part"), data.total(), active.entry.hash());
            }
            active.lastData = System.currentTimeMillis();
            ChunkAssembler.Result r = active.assembler.accept(data.offset(), data.data());
            if (r == ChunkAssembler.Result.OK) {
                long now = System.currentTimeMillis();
                if (now - active.lastMessage > 1000) {
                    active.lastMessage = now;
                    message(Component.translatable("message.icraft_music.share.downloading", active.entry.title(),
                            (int) (100 * active.assembler.written() / active.total)));
                }
                return;
            }
            Download done = active;
            if (r == ChunkAssembler.Result.DONE && done.assembler.verify()) {
                Path target = dir().resolve(done.entry.hash() + "." + done.entry.ext());
                Files.move(done.assembler.partFile(), target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Shared files: downloaded {} ({} bytes)", done.entry.hash().substring(0, 12), done.total);
                active = null;
            } else {
                LOGGER.warn("Shared files: the download of {} was damaged", done.entry.hash().substring(0, 12));
                cancel();
            }
        } catch (IOException e) {
            LOGGER.warn("Shared files: could not store a download", e);
            cancel();
        }
    }

    // keeps the cache folder under its size limit: oldest used files go first
    private static void trimCache(Set<String> keep) {
        try (Stream<Path> list = Files.list(dir())) {
            List<Path> files = new ArrayList<>(list.filter(Files::isRegularFile).toList());
            long total = 0;
            for (Path p : files) total += Files.size(p);
            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));
            for (Path p : files) {
                if (total <= MAX_CACHE_BYTES) break;
                String name = p.getFileName().toString();
                if (name.length() > 64 && keep.contains(name.substring(0, 64))) continue;
                total -= Files.size(p);
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // no cache folder yet
        }
    }
}
