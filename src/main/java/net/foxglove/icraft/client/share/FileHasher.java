package net.foxglove.icraft.client.share;

import net.foxglove.icraft.client.audio.AudioDecoders;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Content hash (SHA-256) and length of a music file. Slow for big files: call from a background thread. */
public final class FileHasher {
    /** What the server needs to know about a file. {@code lengthSeconds} is 0 if the length could not be found. */
    public record Info(String hash, long size, double lengthSeconds) {}

    private record Key(Path path, long size, long modified) {}

    private static final Map<Key, Info> MEMO = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LogUtils.getLogger();

    // Hashes are also kept on disk (<game dir>/icraft_music-hashes.json), so a big album on a network share is read
    // once, not on every play and after every restart. Key: path|size|modified time (a changed file is hashed again).
    private static final int MAX_SAVED = 5000;
    private static final Gson GSON = new Gson();
    private static final Map<String, Info> SAVED = new LinkedHashMap<>(); // insertion order = oldest first
    private static boolean loaded;
    private static boolean dirty;

    private FileHasher() {}

    private static Path savedFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("icraft_music-hashes.json");
    }

    private static String saveKey(Key key) {
        return key.path().toAbsolutePath() + "|" + key.size() + "|" + key.modified();
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path f = savedFile();
        if (!Files.isRegularFile(f)) return;
        try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, Info> read = GSON.fromJson(in, new TypeToken<LinkedHashMap<String, Info>>() {}.getType());
            if (read != null) SAVED.putAll(read);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the saved file hashes; they will be computed again", e);
        }
    }

    /** Writes new hashes to disk (call once after a batch of files, from a background thread). */
    public static synchronized void flush() {
        if (!dirty) return;
        dirty = false;
        while (SAVED.size() > MAX_SAVED) SAVED.remove(SAVED.keySet().iterator().next());
        try (Writer out = Files.newBufferedWriter(savedFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(SAVED, out);
        } catch (IOException e) {
            LOGGER.warn("Could not save the file hashes", e);
        }
    }

    public static Info of(Path file) throws IOException {
        Key key = new Key(file, Files.size(file), Files.getLastModifiedTime(file).toMillis());
        Info known = MEMO.get(key);
        if (known != null) return known;
        load();
        synchronized (FileHasher.class) {
            Info saved = SAVED.get(saveKey(key));
            if (saved != null && saved.hash() != null && saved.hash().matches("[0-9a-f]{64}")) {
                MEMO.put(key, saved);
                return saved;
            }
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        double length = 0;
        try {
            length = AudioDecoders.durationSeconds(file);
        } catch (IOException | RuntimeException ignored) {
            // an unknown length is clamped by the server
        }
        Info info = new Info(HexFormat.of().formatHex(digest.digest()), key.size(), length);
        MEMO.put(key, info);
        synchronized (FileHasher.class) {
            SAVED.remove(saveKey(key));
            SAVED.put(saveKey(key), info); // newest last
            dirty = true;
        }
        return info;
    }
}
