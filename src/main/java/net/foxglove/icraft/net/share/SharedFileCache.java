package net.foxglove.icraft.net.share;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The files players have shared, kept on the server as {@code <hash>.<ext>} in one folder. Server thread only.
 * It is created when the server starts and keeps an index with who uploaded what and when it was last used.
 */
public final class SharedFileCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** What is known about a cached file. */
    public record Info(String hash, String ext, long size, String owner, long lastAccess) {}

    private static SharedFileCache instance;

    private final Path root;
    private final Map<String, Info> files = new LinkedHashMap<>();

    private SharedFileCache(Path root) {
        this.root = root;
    }

    /** The cache of the running server, or null before it started. */
    @Nullable
    public static SharedFileCache get() {
        return instance;
    }

    public static void start(Path root) {
        instance = new SharedFileCache(root);
        instance.load();
    }

    public static void stop() {
        if (instance != null) instance.saveIndex();
        instance = null;
    }

    // ---- reading ----

    @Nullable
    public Info info(String hash) {
        return files.get(hash);
    }

    public Path pathOf(Info info) {
        return root.resolve(info.hash() + "." + info.ext());
    }

    /** Marks the file as used just now (keeps it from being cleaned up). */
    public void touch(String hash) {
        Info i = files.get(hash);
        if (i != null) files.put(hash, new Info(i.hash(), i.ext(), i.size(), i.owner(), System.currentTimeMillis()));
    }

    public long usedBy(UUID player) {
        long total = 0;
        for (Info i : files.values()) if (i.owner().equals(player.toString())) total += i.size();
        return total;
    }

    /** The unfinished upload of this player (one file per player, so two uploads of the same file cannot clash). */
    public Path partFile(String hash, UUID player) {
        return root.resolve(hash + "." + player + ".part");
    }

    // ---- changing ----

    /** Moves a verified upload into the cache. */
    public void add(Path verifiedPart, String hash, String ext, long size, UUID owner) throws IOException {
        Files.createDirectories(root);
        Files.move(verifiedPart, root.resolve(hash + "." + ext), StandardCopyOption.REPLACE_EXISTING);
        files.put(hash, new Info(hash, ext, size, owner.toString(), System.currentTimeMillis()));
        saveIndex();
    }

    /**
     * Deletes old and least recently used files so that a file of {@code extraBytes} fits under {@code maxBytes}.
     * Files in {@code keep} stay. Returns false if it cannot be made to fit.
     */
    public boolean makeRoom(long extraBytes, long maxAgeMillis, long maxBytes, Set<String> keep) {
        List<CachePolicy.Entry> entries = new ArrayList<>();
        for (Info i : files.values()) entries.add(new CachePolicy.Entry(i.hash(), i.size(), i.lastAccess()));
        List<String> delete = CachePolicy.toDelete(entries, System.currentTimeMillis(), maxAgeMillis, maxBytes, extraBytes, keep);
        if (delete == null) return false;
        for (String hash : delete) delete(hash);
        if (!delete.isEmpty()) saveIndex();
        return true;
    }

    private void delete(String hash) {
        Info i = files.remove(hash);
        if (i == null) return;
        try {
            Files.deleteIfExists(pathOf(i));
            LOGGER.info("Shared file cache: deleted {} ({} bytes)", hash.substring(0, 12), i.size());
        } catch (IOException e) {
            LOGGER.warn("Could not delete the shared file {}", hash, e);
        }
    }

    // ---- saving ----

    private void load() {
        Map<String, Info> saved = new LinkedHashMap<>();
        Path index = root.resolve("index.json");
        if (Files.isRegularFile(index)) {
            try (Reader in = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
                Map<String, Info> read = GSON.fromJson(in, new TypeToken<LinkedHashMap<String, Info>>() {}.getType());
                if (read != null) saved = read;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read the shared file index; rebuilding it from the folder", e);
            }
        }
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> list = Files.list(root)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot != 64 || !Files.isRegularFile(p)) { // <64 hex>.<ext>
                    if (name.endsWith(".part")) Files.deleteIfExists(p); // an unfinished upload from before a restart
                    continue;
                }
                String hash = name.substring(0, 64), ext = name.substring(65);
                Info known = saved.get(hash);
                files.put(hash, known != null ? known : new Info(hash, ext, Files.size(p), "unknown",
                        Files.getLastModifiedTime(p).toMillis()));
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list the shared file cache {}", root, e);
        }
        LOGGER.info("Shared file cache: {} files in {}", files.size(), root);
    }

    public void saveIndex() {
        try {
            Files.createDirectories(root);
            try (Writer out = Files.newBufferedWriter(root.resolve("index.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(files, out);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save the shared file index", e);
        }
    }
}
