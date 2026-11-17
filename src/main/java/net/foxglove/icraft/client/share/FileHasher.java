package net.foxglove.icraft.client.share;

import net.foxglove.icraft.client.audio.AudioDecoders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Content hash (SHA-256) and length of a music file. Slow for big files: call from a background thread. */
public final class FileHasher {
    /** What the server needs to know about a file. {@code lengthSeconds} is 0 if the length could not be found. */
    public record Info(String hash, long size, double lengthSeconds) {}

    private record Key(Path path, long size, long modified) {}

    private static final Map<Key, Info> MEMO = new ConcurrentHashMap<>();

    private FileHasher() {}

    public static Info of(Path file) throws IOException {
        Key key = new Key(file, Files.size(file), Files.getLastModifiedTime(file).toMillis());
        Info known = MEMO.get(key);
        if (known != null) return known;

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
        return info;
    }
}
