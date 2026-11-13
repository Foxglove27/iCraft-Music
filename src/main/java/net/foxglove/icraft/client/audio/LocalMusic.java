package net.foxglove.icraft.client.audio;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class LocalMusic {
    public record Album(String name, @Nullable Path cover, List<Path> files) {}

    private static final Set<String> AUDIO = Set.of("ogg", "mp3", "flac", "wav", "m4a");
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
                albums.add(new Album("Singles", null, singles));
            }

            List<Path> dirs;
            try (Stream<Path> stream = Files.list(root)) {
                dirs = stream.filter(Files::isDirectory)
                        .filter(d -> !d.getFileName().toString().startsWith(".")) // skips .cache
                        .sorted()
                        .toList();
            }
            for (Path dir : dirs) {
                List<Path> files = listAudio(dir);
                if (!files.isEmpty()) {
                    albums.add(new Album(dir.getFileName().toString(), findCover(dir), files));
                }
            }
        } catch (IOException ignored) {
        }
        return albums;
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
}