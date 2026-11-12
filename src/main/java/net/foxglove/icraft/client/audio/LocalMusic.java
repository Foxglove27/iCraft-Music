package net.foxglove.icraft.client.audio;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class LocalMusic {
    private static final Set<String> EXTENSIONS = Set.of("ogg", "mp3", "flac", "wav", "m4a");

    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("ipod_music");
    }

    private static boolean isSupported(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1)) && Files.isRegularFile(p);
    }

    // creates the folder on first use
    public static List<Path> list() {
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(LocalMusic::isSupported).sorted().toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String displayName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}