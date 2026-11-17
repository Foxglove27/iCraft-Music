package net.foxglove.icraft.client.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the player stopped in each audiobook chapter. Saved on the client in
 * <code>icraft-progress.json</code> in the game folder:
 * <pre>{ "audiobooks/Book": { "last": "audiobooks/Book/ch2.mp3", "pos": { "audiobooks/Book/ch2.mp3": 123.4 } } }</pre>
 * Every problem with the file is logged and ignored: losing progress must never break playback.
 */
public final class AudiobookProgress {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class BookState {
        String last;
        Map<String, Double> pos = new LinkedHashMap<>();
    }

    private static Map<String, BookState> books;

    private AudiobookProgress() {}

    // Progress is kept in the game folder, never in the library (which may be a read-only network share)
    private static Path file() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("icraft-progress.json");
    }

    // The file older versions wrote into the library folder; only read, once, if the new file does not exist yet
    private static Path legacyFile() {
        return LocalMusic.libraryRoot().resolve(".icraft-progress.json");
    }

    private static Map<String, BookState> load() {
        if (books != null) return books;
        books = new LinkedHashMap<>();
        Path file = file();
        if (!Files.isRegularFile(file) && Files.isRegularFile(legacyFile())) file = legacyFile();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, BookState> read = GSON.fromJson(in,
                        new com.google.gson.reflect.TypeToken<LinkedHashMap<String, BookState>>() {}.getType());
                if (read != null) books = read;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read the audiobook progress file {}; starting without it", file, e);
                books = new LinkedHashMap<>();
            }
        }
        return books;
    }

    private static void write() {
        Path file = file();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(books, out);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not save the audiobook progress to {}", file, e);
        }
    }

    /** Seconds into the chapter where it was left, or 0. */
    public static double get(String bookKey, String chapterKey) {
        BookState b = load().get(bookKey);
        Double p = b == null ? null : b.pos.get(chapterKey);
        return p == null ? 0 : p;
    }

    /** The chapter that was played last in this book, or null. */
    @Nullable
    public static String lastChapter(String bookKey) {
        BookState b = load().get(bookKey);
        return b == null ? null : b.last;
    }

    /** Remembers the position (seconds into the chapter) and that this chapter was the last one played. */
    public static void save(String bookKey, String chapterKey, double seconds) {
        BookState b = load().computeIfAbsent(bookKey, k -> new BookState());
        b.last = chapterKey;
        b.pos.remove(chapterKey);
        b.pos.put(chapterKey, Math.round(seconds * 10) / 10.0);
        write();
    }

    /** The chapter was played to the end: forget its position and make the next chapter the last one. */
    public static void finish(String bookKey, String chapterKey, @Nullable String nextChapterKey) {
        BookState b = load().computeIfAbsent(bookKey, k -> new BookState());
        b.pos.remove(chapterKey);
        b.last = nextChapterKey != null ? nextChapterKey : chapterKey;
        write();
    }
}
