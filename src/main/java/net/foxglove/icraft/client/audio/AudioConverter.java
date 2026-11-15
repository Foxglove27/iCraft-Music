package net.foxglove.icraft.client.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AudioConverter {

    // Returns a playable .ogg for the given file, converting (and caching) if needed.
    // synchronized: only one conversion runs at a time, so two requests for the same file
    // can never write the same temporary file. The second caller finds the finished result.
    public static synchronized Path toOgg(Path source) throws IOException {
        String name = source.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            return source;
        }

        Path cacheDir = source.getParent().resolve(".cache");
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(name + ".ogg"); // song.mp3 and song.flac stay distinct

        if (Files.exists(target)
                && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            return target; // already converted and up to date
        }

        Path tmp = cacheDir.resolve(name + ".tmp.ogg");
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-loglevel", "error",
                "-i", source.toString(), "-vn", "-c:a", "libvorbis", "-q:a", "5", tmp.toString());
        pb.inheritIO(); // ffmpeg errors show up in the game console

        try {
            Process process = pb.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out converting " + name);
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg failed to convert " + name);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp); // don't leave a half-written file behind
            throw new IOException("Could not convert " + name + " (is ffmpeg installed and on PATH?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while converting " + name, e);
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}