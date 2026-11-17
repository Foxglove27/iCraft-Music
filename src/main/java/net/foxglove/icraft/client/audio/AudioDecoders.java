package net.foxglove.icraft.client.audio;

import net.foxglove.icraft.client.audio.decode.AacSource;
import net.foxglove.icraft.client.audio.decode.AlacSource;
import net.foxglove.icraft.client.audio.decode.Mp4File;
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
            case "m4a", "m4b" -> openMp4(file);
            default -> throw new IOException("Unsupported audio format: " + file.getFileName());
        };
        return new PcmAudioStream(source, downmixToMono);
    }

    /** How long a file plays, in seconds; 0 if the format does not tell. Opens the file briefly. */
    public static double durationSeconds(Path file) throws IOException {
        PcmAudioStream stream = open(file, false);
        try {
            long frames = stream.lengthFrames();
            return frames > 0 ? (double) frames / stream.sampleRate() : 0;
        } finally {
            stream.close();
        }
    }

    // .m4a and .m4b hold AAC or Apple Lossless: read the index once and pick the decoder by the codec
    private static PcmSource openMp4(Path file) throws IOException {
        Mp4File mp4 = Mp4File.open(file);
        return switch (mp4.codec()) {
            case AAC -> new AacSource(mp4);
            case ALAC -> new AlacSource(mp4);
        };
    }
}
