package net.foxglove.icraft.client.share;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** The player's own files by content hash, so a dock they own plays their originals and never downloads them. */
public final class LocalFileIndex {
    private static final Map<String, Path> FILES = new HashMap<>();

    private LocalFileIndex() {}

    public static void put(String hash, Path file) {
        FILES.put(hash, file);
    }

    @Nullable
    public static Path get(String hash) {
        Path p = FILES.get(hash);
        return p != null && java.nio.file.Files.isRegularFile(p) ? p : null;
    }
}
