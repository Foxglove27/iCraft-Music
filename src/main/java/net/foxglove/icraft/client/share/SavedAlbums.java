package net.foxglove.icraft.client.share;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.foxglove.icraft.client.DockControl.FileItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The album of the last long list (longer than the server's queue limit) that the player started at each dock, kept in
 * <game dir>/icraft_music-albums.json so that skipping can go through the whole album again after a game restart.
 * Only the game folder is written, never the library. Titles are stored as plain text.
 */
public final class SavedAlbums {
    /** One saved album: where the server's window starts and every track of the album. */
    public record Album(int offset, boolean book, List<FileItem> items) {}

    // The file format (Gson plain objects; Path and Component are stored as text)
    private static final class Entry {
        int offset;
        boolean book;
        List<Item> items = new ArrayList<>();
    }

    private static final class Item {
        String file;
        String title;
        String album;
        String cover;
        double start;
        double end;
        String progressBook;
        String progressKey;
        String nextKey;
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final int MAX_DOCKS = 20;
    private static final Map<String, Entry> SAVED = new LinkedHashMap<>(); // oldest first
    private static boolean loaded;

    private SavedAlbums() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("icraft_music-albums.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, Entry> read = GSON.fromJson(in, new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
            if (read != null) SAVED.putAll(read);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the saved albums; they are forgotten", e);
        }
    }

    private static void write() {
        try (Writer out = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            GSON.toJson(SAVED, out);
        } catch (IOException e) {
            LOGGER.warn("Could not save the albums", e);
        }
    }

    /** Remembers the album of the dock {@code key}. Call from a background thread. */
    public static synchronized void save(String key, int offset, boolean book, List<FileItem> album) {
        load();
        Entry e = new Entry();
        e.offset = offset;
        e.book = book;
        for (FileItem i : album) {
            Item it = new Item();
            it.file = i.file().toString();
            it.title = i.title().getString();
            it.album = i.album().getString();
            it.cover = i.cover() == null ? null : i.cover().toString();
            it.start = i.startSeconds();
            it.end = i.endSeconds();
            it.progressBook = i.progressBook();
            it.progressKey = i.progressKey();
            it.nextKey = i.nextKey();
            e.items.add(it);
        }
        SAVED.remove(key);
        SAVED.put(key, e); // newest last
        while (SAVED.size() > MAX_DOCKS) SAVED.remove(SAVED.keySet().iterator().next());
        write();
    }

    /** Forgets the album of the dock {@code key} (a list that is not partial, or an album that no longer matches). */
    public static synchronized void remove(String key) {
        load();
        if (SAVED.remove(key) != null) write();
    }

    /** The saved album of the dock {@code key}, or null if there is none or it is damaged. */
    @Nullable
    public static synchronized Album get(String key) {
        load();
        Entry e = SAVED.get(key);
        if (e == null || e.items == null || e.items.isEmpty()) return null;
        try {
            List<FileItem> items = new ArrayList<>();
            for (Item i : e.items) {
                if (i.file == null || i.progressKey == null) return null;
                items.add(new FileItem(Path.of(i.file), Component.literal(i.title == null ? "" : i.title),
                        Component.literal(i.album == null ? "" : i.album), i.cover == null ? null : Path.of(i.cover),
                        i.start, i.end, i.progressBook, i.progressKey, i.nextKey));
            }
            return new Album(e.offset, e.book, items);
        } catch (RuntimeException ex) { // a bad path in a hand-edited file
            return null;
        }
    }
}
