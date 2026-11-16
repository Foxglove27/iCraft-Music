package net.foxglove.icraft.client.audio;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class LocalMusic {
    public record Album(Component name, @Nullable Path cover, List<Path> files) {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean encodingWarned;

    private static final Set<String> AUDIO = Set.of("ogg", "mp3", "flac", "wav");
    private static final Set<String> COVERS = Set.of("cover.png", "cover.jpg", "cover.jpeg",
            "folder.png", "folder.jpg", "folder.jpeg");

    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("ipod_music");
    }

    // creates the folder on first use
    public static List<Album> albums() {
        Path root = folder();
        List<Album> albums = new ArrayList<>();
        try {
            Files.createDirectories(root);

            List<Path> singles = listAudio(root);
            if (!singles.isEmpty()) {
                albums.add(new Album(Component.translatable("screen.icraft.ipod.singles"), null, singles));
            }

            List<Path> dirs;
            try (Stream<Path> stream = Files.list(root)) {
                dirs = stream.filter(Files::isDirectory)
                        .filter(d -> !d.getFileName().toString().startsWith(".")) // skips .cache
                        .sorted()
                        .toList();
            }
            for (Path dir : dirs) {
                checkEncoding(dir);
                List<Path> files = listAudio(dir);
                if (!files.isEmpty()) {
                    albums.add(new Album(Component.literal(dir.getFileName().toString()), findCover(dir), files));
                }
            }
        } catch (IOException ignored) {
        }
        return albums;
    }

    /**
     * On Linux/macOS the JVM decodes file names with the locale of the process that started it.
     * A launcher without a UTF-8 locale turns every non-ASCII character (like the I with a bar in
     * AVICI) into '?', and Java can then no longer open or list those files. Nothing in the mod can
     * recover the real name, so say what to do, once.
     */
    private static void checkEncoding(Path entry) {
        if (encodingWarned) return;
        String name = entry.getFileName().toString();
        String jnu = System.getProperty("sun.jnu.encoding", "");
        if ((name.indexOf('?') >= 0 || name.indexOf('\uFFFD') >= 0) && !jnu.equalsIgnoreCase("UTF-8")) {
            encodingWarned = true;
            LOGGER.warn("The file name '{}' contains characters Java cannot read (file name encoding is {}). "
                    + "Files and folders with non-ASCII names will not play. Start Minecraft with a UTF-8 "
                    + "locale, e.g. set the environment variable LC_ALL=C.UTF-8 in your launcher.", name, jnu);
        }
    }

    private static List<Path> listAudio(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(LocalMusic::isAudio).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isAudio(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO.contains(name.substring(dot + 1)) && Files.isRegularFile(p);
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

    // The album a file belongs to: its folder name, or "Singles" for loose files
    public static Component albumNameFor(Path file) {
        Path dir = file.getParent();
        if (dir == null || dir.equals(folder())) return Component.translatable("screen.icraft.ipod.singles");
        return Component.literal(dir.getFileName().toString());
    }

    // The cover image in a file's album folder, or null if there isn't one
    @Nullable
    public static Path coverFor(Path file) {
        Path dir = file.getParent();
        if (dir == null || dir.equals(folder())) return null; // loose files have no album folder
        return findCover(dir);
    }
}