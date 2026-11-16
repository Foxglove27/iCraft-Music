package net.foxglove.icraft.client.audio;

import net.foxglove.icraft.client.audio.decode.FlacSource;
import net.foxglove.icraft.client.audio.decode.Mp3Source;
import net.foxglove.icraft.client.audio.decode.OggSource;
import net.foxglove.icraft.client.audio.decode.PcmSource;
import net.foxglove.icraft.client.audio.decode.WavSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Opens a music file with the decoder for its format. Replaces the old ffmpeg conversion. */
public final class AudioDecoders {
    private AudioDecoders() {}

    public static PcmAudioStream open(Path file, boolean downmixToMono) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        PcmSource source = switch (ext) {
            case "ogg" -> new OggSource(file);
            case "mp3" -> new Mp3Source(file);
            case "flac" -> new FlacSource(file);
            case "wav" -> new WavSource(file);
            default -> throw new IOException("Unsupported audio format: " + file.getFileName());
        };
        return new PcmAudioStream(source, downmixToMono);
    }
}
