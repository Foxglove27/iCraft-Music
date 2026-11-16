package net.foxglove.icraft.client.audio;

import com.mojang.logging.LogUtils;
import net.foxglove.icraft.client.audio.decode.Mp4File;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The player's own library, read from <code>&lt;game directory&gt;/ipod</code>:
 * <ul>
 *   <li><code>ipod/music/&lt;album&gt;/...</code> (loose files are the "Singles" album)</li>
 *   <li><code>ipod/audiobooks/&lt;book&gt;/...</code> (one folder per book, chapters as files, or one .m4b with chapter markers)</li>
 * </ul>
 * The old <code>ipod_music</code> folder is still read as an extra music folder.
 */
public class LocalMusic {
    public record Album(Component name, @Nullable Path cover, List<Path> files) {}

    /**
     * One entry in a book: a whole file, or one chapter marker inside a .m4b.
     * {@code endSeconds} is -1 for "until the end of the file". {@code key} identifies the chapter
     * in the saved progress.
     */
    public record Chapter(Path file, Component title, double startSeconds, double endSeconds, String key) {}

    public record Book(Component name, @Nullable Path cover, List<Chapter> chapters, String key) {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> AUDIO = Set.of("ogg", "mp3", "flac", "wav", "m4a", "m4b");
    private static final Set<String> MP4_EXT = Set.of("m4a", "m4b");
    private static final Set<String> COVERS = Set.of("cover.png", "cover.jpg", "cover.jpeg",
            "folder.png", "folder.jpg", "folder.jpeg");

    private static boolean legacyLogged;

    // ---- folders ----

    public static Path libraryRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("ipod");
    }

    public static Path musicRoot() {
        return libraryRoot().resolve("music");
    }

    public static Path podcastRoot() {
        return libraryRoot().resolve("podcasts");
    }

    public static Path audiobookRoot() {
        return libraryRoot().resolve("audiobooks");
    }

    /** The old folder from before audiobooks existed. Only used when it is there. */
    private static Path legacyMusicRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("ipod_music");
    }

    /** All folders music is read from: ipod/music, plus the old ipod_music if it exists. */
    public static List<Path> musicRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(musicRoot());
        if (Files.isDirectory(legacyMusicRoot())) {
            roots.add(legacyMusicRoot());
            if (!legacyLogged) {
                legacyLogged = true;
                LOGGER.info("Also reading music from the old folder {}. You can move it to {}.",
                        legacyMusicRoot(), musicRoot());
            }
        }
        return roots;
    }

    // ---- music ----

    // creates the folders on first use
    public static List<Album> albums() {
        List<Album> albums = new ArrayList<>();
        List<Path> singles = new ArrayList<>();
        try {
            Files.createDirectories(musicRoot());
            Files.createDirectories(audiobookRoot());
            Files.createDirectories(podcastRoot());
        } catch (IOException e) {
            LOGGER.warn("Could not create the library folders under {}", libraryRoot(), e);
        }

        for (Path root : musicRoots()) {
            singles.addAll(listAudio(root));
            for (Path dir : listFolders(root)) {
                List<Path> files = listAudio(dir);
                if (!files.isEmpty()) {
                    albums.add(new Album(Component.literal(dir.getFileName().toString()), findCover(dir), files));
                }
            }
        }
        if (!singles.isEmpty()) {
            albums.add(0, new Album(Component.translatable("screen.icraft.ipod.singles"), null, singles));
        }
        return albums;
    }

    // ---- audiobooks ----

    public static List<Book> books() {
        return scanBooks(audiobookRoot());
    }

    // ---- podcasts: shows are folders and episodes are files, exactly like books and chapters ----

    public static List<Book> podcasts() {
        return scanBooks(podcastRoot());
    }

    private static List<Book> scanBooks(Path root) {
        List<Book> books = new ArrayList<>();
        for (Path dir : listFolders(root)) {
            List<Chapter> chapters = new ArrayList<>();
            for (Path file : listAudio(dir)) {
                chapters.addAll(chaptersOf(file));
            }
            if (!chapters.isEmpty()) {
                books.add(new Book(Component.literal(dir.getFileName().toString()), findCover(dir), chapters, key(dir)));
            }
        }
        return books;
    }

    // marker parsing is cached per file (path, size, modification time)
    private record Stamp(long size, long modified) {}
    private static final Map<Path, Stamp> CACHE_STAMP = new HashMap<>();
    private static final Map<Path, List<Chapter>> CACHE = new HashMap<>();

    private static List<Chapter> chaptersOf(Path file) {
        String ext = extension(file);
        if (MP4_EXT.contains(ext)) {
            try {
                Stamp stamp = new Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis());
                if (stamp.equals(CACHE_STAMP.get(file))) return CACHE.get(file);
                List<Chapter> chapters = readMarkers(file);
                CACHE_STAMP.put(file, stamp);
                CACHE.put(file, chapters);
                return chapters;
            } catch (IOException e) {
                // fall through to a plain chapter
            }
        }
        return List.of(plainChapter(file));
    }

    private static Chapter plainChapter(Path file) {
        return new Chapter(file, Component.literal(displayName(file)), 0, -1, key(file));
    }

    private static List<Chapter> readMarkers(Path file) {
        List<Mp4File.Marker> markers = Mp4File.readMarkers(file);
        if (markers.size() < 2) return List.of(plainChapter(file));
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < markers.size(); i++) {
            Mp4File.Marker m = markers.get(i);
            double end = i + 1 < markers.size() ? markers.get(i + 1).startSeconds() : -1;
            String title = m.title().isBlank() ? displayName(file) + " " + (i + 1) : m.title();
            chapters.add(new Chapter(file, Component.literal(title), m.startSeconds(), end, key(file) + "#" + i));
        }
        return chapters;
    }

    /** The path relative to the ipod folder, with forward slashes. Identifies a file or book in saved progress. */
    public static String key(Path path) {
        try {
            return libraryRoot().relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString().replace('\\', '/');
        }
    }

    /** True for a file inside the audiobook folder. */
    public static boolean isAudiobookFile(Path file) {
        return file.startsWith(audiobookRoot());
    }

    // ---- scanning ----

    private static List<Path> listFolders(Path root) {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith(".")) // hidden folders such as .cache
                    .sorted(NATURAL)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> listAudio(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(LocalMusic::isAudio).sorted(NATURAL).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isAudio(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO.contains(name.substring(dot + 1)) && Files.isRegularFile(p);
    }

    private static String extension(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Sorts "Chapter 2" before "Chapter 10": runs of digits are compared as numbers, the rest ignoring case. */
    static final Comparator<Path> NATURAL = (a, b) ->
            naturalCompare(a.getFileName().toString(), b.getFileName().toString());

    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        int c = Integer.compare(a.length() - i, b.length() - j);
        return c != 0 ? c : a.compareTo(b);
    }

    @Nullable
    private static Path findCover(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> COVERS.contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static String displayName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isDirectlyInMusicRoot(Path file) {
        Path dir = file.getParent();
        if (dir == null) return true;
        for (Path root : musicRoots()) {
            if (dir.equals(root)) return true;
        }
        return false;
    }

    // The album (or book) a file belongs to: its folder name, or "Singles" for loose music files
    public static Component albumNameFor(Path file) {
        if (isDirectlyInMusicRoot(file)) return Component.translatable("screen.icraft.ipod.singles");
        return Component.literal(file.getParent().getFileName().toString());
    }

    // The cover image in a file's folder, or null if there isn't one
    @Nullable
    public static Path coverFor(Path file) {
        if (isDirectlyInMusicRoot(file)) return null; // loose files have no album folder
        return findCover(file.getParent());
    }
}
