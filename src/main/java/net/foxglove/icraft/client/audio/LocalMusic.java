package net.foxglove.icraft.client.audio;

import com.mojang.logging.LogUtils;
import net.foxglove.icraft.ClientConfig;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The player's own library. Per section (music, audiobooks, podcasts) it reads
 * <code>&lt;game directory&gt;/ipod/&lt;section&gt;</code> plus any extra folders from the client config
 * (for example a mounted NAS share), and for music the old <code>ipod_music</code> folder.
 * <ul>
 *   <li>A folder that directly contains audio files is an album (or book, or show). Folders are searched up to
 *       {@link #MAX_DEPTH} levels below a root; <code>Artist/Album</code> is shown as "Artist - Album".</li>
 *   <li>Loose music files in a music root are the "Singles" album.</li>
 *   <li>A book's chapters are files, or one .m4b whose chapter markers become entries.</li>
 * </ul>
 * The scan can be slow on a network share, so it runs on a background thread ({@link LibraryCache}); the
 * mod never writes into the library (only the local <code>ipod</code> folders are created).
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

    /** The three parts of the library; {@code folder} is the folder name below <code>ipod/</code> and the start of progress keys. */
    public enum Section {
        MUSIC("music"), AUDIOBOOKS("audiobooks"), PODCASTS("podcasts");

        public final String folder;

        Section(String folder) {
            this.folder = folder;
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How many folder levels below a root are searched (Artist/Album/Disc). */
    public static final int MAX_DEPTH = 3;

    private static final Set<String> AUDIO = Set.of("ogg", "mp3", "flac", "wav", "m4a", "m4b");
    private static final Set<String> MP4_EXT = Set.of("m4a", "m4b");
    private static final Set<String> COVERS = Set.of("cover.png", "cover.jpg", "cover.jpeg",
            "folder.png", "folder.jpg", "folder.jpeg");

    private static boolean legacyLogged;
    private static final Set<Path> REPORTED = ConcurrentHashMap.newKeySet(); // extra folders already complained about

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

    // The extra folders from the config as paths (absolute, or relative to the game directory). No disk access.
    private static List<Path> extraRoots(Section section) {
        List<? extends String> configured;
        try {
            configured = switch (section) {
                case MUSIC -> ClientConfig.EXTRA_MUSIC_FOLDERS.get();
                case AUDIOBOOKS -> ClientConfig.EXTRA_AUDIOBOOK_FOLDERS.get();
                case PODCASTS -> ClientConfig.EXTRA_PODCAST_FOLDERS.get();
            };
        } catch (IllegalStateException e) {
            return List.of(); // the config is not loaded (yet)
        }
        Path game = Minecraft.getInstance().gameDirectory.toPath();
        List<Path> roots = new ArrayList<>();
        for (String text : configured) {
            if (text == null || text.isBlank()) continue;
            if (text.trim().matches("(?i)^[a-z][a-z0-9+.-]+://.*")) { // smb://, nfs://, http://...: not a file path
                if (REPORTED.add(Path.of("url:" + text))) {
                    LOGGER.warn("The library folder '{}' is a network address, not a folder. Mount the share first "
                            + "(for example to /mnt/nas-music) and put the mount path in the config.", text);
                }
                continue;
            }
            try {
                roots.add(game.resolve(text.trim()).normalize());
            } catch (RuntimeException e) {
                if (REPORTED.add(Path.of("invalid:" + text))) LOGGER.warn("Ignoring the invalid library folder '{}'", text);
            }
        }
        return roots;
    }

    /** The folders of a section that exist: the local one, for music the old ipod_music, then the extra folders. */
    public static List<Path> roots(Section section) {
        List<Path> roots = new ArrayList<>();
        roots.add(libraryRoot().resolve(section.folder));
        if (section == Section.MUSIC && Files.isDirectory(legacyMusicRoot())) {
            roots.add(legacyMusicRoot());
            if (!legacyLogged) {
                legacyLogged = true;
                LOGGER.info("Also reading music from the old folder {}. You can move it to {}.",
                        legacyMusicRoot(), musicRoot());
            }
        }
        for (Path extra : extraRoots(section)) {
            if (Files.isDirectory(extra)) {
                roots.add(extra);
            } else if (REPORTED.add(extra)) {
                LOGGER.warn("The library folder {} does not exist or cannot be read; skipping it (a launcher in a sandbox such as Flatpak or Snap needs access to this path)", extra);
            }
        }
        return roots;
    }

    // The roots without touching the disk (for names and keys while scanning)
    private static List<Path> rawRoots(Section section) {
        List<Path> roots = new ArrayList<>();
        roots.add(libraryRoot().resolve(section.folder));
        if (section == Section.MUSIC) roots.add(legacyMusicRoot());
        roots.addAll(extraRoots(section));
        return roots;
    }

    // creates the local folders on first use (never anything inside an extra folder)
    private static void ensureLocalFolders() {
        try {
            Files.createDirectories(musicRoot());
            Files.createDirectories(audiobookRoot());
            Files.createDirectories(podcastRoot());
        } catch (IOException e) {
            LOGGER.warn("Could not create the library folders under {}", libraryRoot(), e);
        }
    }

    // ---- scanning (slow on a network share: call from a background thread) ----

    // cover of every album folder found by the last scans, so playing a file needs no directory listing
    private static final Map<Path, Optional<Path>> COVER_OF_DIR = new ConcurrentHashMap<>();

    /** The music of the local folders: ipod/music and the old ipod_music (the extra folders are {@link #nasAlbums()}). */
    public static List<Album> albums() {
        ensureLocalFolders();
        List<Album> albums = new ArrayList<>();
        List<Path> singles = new ArrayList<>();
        for (Path root : roots(Section.MUSIC)) {
            if (isExtra(Section.MUSIC, root)) continue;
            singles.addAll(listAudio(root));
            scanFolders(root, (dir, name, files) ->
                    albums.add(new Album(Component.literal(name), coverOf(dir), files)));
        }
        if (!singles.isEmpty()) {
            albums.add(0, new Album(Component.translatable("screen.icraft_music.ipod.singles"), null, singles));
        }
        return albums;
    }

    /** The music of the extra music folders from the config (a NAS), shown on its own page. */
    public static List<Album> nasAlbums() {
        List<Album> albums = new ArrayList<>();
        for (Path root : roots(Section.MUSIC)) {
            if (!isExtra(Section.MUSIC, root)) continue;
            List<Path> loose = listAudio(root);
            if (!loose.isEmpty()) {
                Path name = root.getFileName();
                albums.add(new Album(Component.literal(name != null ? name.toString() : root.toString()), coverOf(root), loose));
            }
            scanFolders(root, (dir, name, files) ->
                    albums.add(new Album(Component.literal(name), coverOf(dir), files)));
        }
        return albums;
    }

    /** True if any extra folder is set in the config (the iPod then shows the NAS entry). */
    public static boolean hasNasFolders() {
        return hasNasFolders(Section.MUSIC) || hasNasFolders(Section.AUDIOBOOKS) || hasNasFolders(Section.PODCASTS);
    }

    /** True if an extra folder is set for this section. */
    public static boolean hasNasFolders(Section section) {
        return !extraRoots(section).isEmpty();
    }

    private static boolean isExtra(Section section, Path root) {
        return extraRoots(section).contains(root);
    }

    /** Audiobooks of the local folder (ipod/audiobooks). */
    public static List<Book> books() {
        return scanBooks(Section.AUDIOBOOKS, false);
    }

    // podcasts: shows are folders and episodes are files, exactly like books and chapters
    public static List<Book> podcasts() {
        return scanBooks(Section.PODCASTS, false);
    }

    /** Audiobooks of the extra audiobook folders from the config (the NAS). */
    public static List<Book> nasBooks() {
        return scanBooks(Section.AUDIOBOOKS, true);
    }

    /** Podcasts of the extra podcast folders from the config (the NAS). */
    public static List<Book> nasPodcasts() {
        return scanBooks(Section.PODCASTS, true);
    }

    private interface FolderVisitor {
        void found(Path dir, String name, List<Path> audioFiles);
    }

    // Calls the visitor for every folder below the root (up to MAX_DEPTH) that directly holds audio files.
    private static void scanFolders(Path root, FolderVisitor visitor) {
        scanFolders(root, root, 1, visitor);
    }

    private static void scanFolders(Path root, Path dir, int depth, FolderVisitor visitor) {
        for (Path sub : listFolders(dir)) {
            List<Path> files = listAudio(sub);
            if (!files.isEmpty()) visitor.found(sub, nameBelow(root, sub), files);
            if (depth < MAX_DEPTH) scanFolders(root, sub, depth + 1, visitor);
        }
    }

    // "Artist/Album" below the root becomes "Artist - Album"; a folder directly in the root keeps its name
    private static String nameBelow(Path root, Path dir) {
        Path rel = root.relativize(dir);
        StringBuilder name = new StringBuilder();
        for (Path part : rel) {
            if (name.length() > 0) name.append(" - ");
            name.append(part);
        }
        return name.toString();
    }

    private static List<Book> scanBooks(Section section, boolean extra) {
        List<Book> books = new ArrayList<>();
        for (Path root : roots(section)) {
            if (isExtra(section, root) != extra) continue;
            scanFolders(root, (dir, name, files) -> {
                List<Chapter> chapters = new ArrayList<>();
                for (Path file : files) chapters.addAll(chaptersOf(file));
                if (!chapters.isEmpty()) books.add(new Book(Component.literal(name), coverOf(dir), chapters, key(dir)));
            });
        }
        return books;
    }

    @Nullable
    private static Path coverOf(Path dir) {
        Path cover = findCover(dir);
        COVER_OF_DIR.put(dir, Optional.ofNullable(cover));
        return cover;
    }

    // marker parsing is cached per file (path, size, modification time)
    private record Stamp(long size, long modified) {}
    private static final Map<Path, Stamp> CACHE_STAMP = new ConcurrentHashMap<>();
    private static final Map<Path, List<Chapter>> CACHE = new ConcurrentHashMap<>();

    private static List<Chapter> chaptersOf(Path file) {
        String ext = extension(file);
        if (MP4_EXT.contains(ext)) {
            try {
                Stamp stamp = new Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis());
                List<Chapter> cached = CACHE.get(file);
                if (cached != null && stamp.equals(CACHE_STAMP.get(file))) return cached;
                List<Chapter> chapters = readMarkers(file);
                CACHE.put(file, chapters);
                CACHE_STAMP.put(file, stamp);
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

    /**
     * Identifies a file or folder in saved progress. For the local layout and the old ipod_music it is the path
     * relative to the ipod folder (as always); for a file in an extra folder it is the section name plus the
     * path below that folder. Forward slashes.
     */
    public static String key(Path path) {
        for (Section section : Section.values()) {
            for (Path extra : extraRoots(section)) {
                if (path.startsWith(extra)) {
                    return section.folder + "/" + extra.relativize(path).toString().replace('\\', '/');
                }
            }
        }
        try {
            return libraryRoot().relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString().replace('\\', '/');
        }
    }

    /** True for a file inside an audiobook folder. */
    public static boolean isAudiobookFile(Path file) {
        for (Path root : rawRoots(Section.AUDIOBOOKS)) {
            if (file.startsWith(root)) return true;
        }
        return false;
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

    // The root of a section that holds this file, or null
    @Nullable
    private static Path rootOf(Section section, Path file) {
        for (Path root : rawRoots(section)) {
            if (file.startsWith(root)) return root;
        }
        return null;
    }

    private static boolean isDirectlyInMusicRoot(Path file) {
        Path dir = file.getParent();
        if (dir == null) return true;
        for (Path root : rawRoots(Section.MUSIC)) {
            if (dir.equals(root)) return true;
        }
        return false;
    }

    // The album (or book) a file belongs to: its folder name (with the artist folders in front), or "Singles" for loose music files
    public static Component albumNameFor(Path file) {
        if (isDirectlyInMusicRoot(file)) return Component.translatable("screen.icraft_music.ipod.singles");
        Path dir = file.getParent();
        for (Section section : Section.values()) {
            Path root = rootOf(section, file);
            if (root != null && dir.startsWith(root) && !dir.equals(root)) return Component.literal(nameBelow(root, dir));
        }
        return Component.literal(dir.getFileName().toString());
    }

    // The cover image in a file's folder, or null if there isn't one (answered from the scan, no disk access)
    @Nullable
    public static Path coverFor(Path file) {
        if (isDirectlyInMusicRoot(file)) return null; // loose files have no album folder
        Optional<Path> known = COVER_OF_DIR.get(file.getParent());
        return known != null ? known.orElse(null) : findCover(file.getParent());
    }
}
