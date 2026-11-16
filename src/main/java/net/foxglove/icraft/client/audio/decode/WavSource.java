package net.foxglove.icraft.client.audio.decode;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;

/** WAV through the JDK's own javax.sound, converted to 16-bit. Streams from disk. */
public final class WavSource implements PcmSource {
    private final Path path;
    private AudioInputStream in;
    private final int sampleRate;
    private final int channels;
    private final long length;
    private byte[] bytes = new byte[0];

    public WavSource(Path path) throws IOException {
        this.path = path;
        in = open();
        AudioFormat f = in.getFormat();
        sampleRate = Math.round(f.getSampleRate());
        channels = f.getChannels();
        length = in.getFrameLength() >= 0 ? in.getFrameLength() : -1;
        if (channels < 1 || channels > 2) {
            in.close();
            throw new IOException("Only mono or stereo files are supported");
        }
    }

    private AudioInputStream open() throws IOException {
        try {
            AudioInputStream raw = AudioSystem.getAudioInputStream(path.toFile());
            AudioFormat f = raw.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), 16,
                    f.getChannels(), f.getChannels() * 2, f.getSampleRate(), false);
            return AudioSystem.getAudioInputStream(target, raw);
        } catch (UnsupportedAudioFileException | IllegalArgumentException e) {
            throw new IOException("Unsupported WAV file: " + e.getMessage(), e);
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public long lengthFrames() {
        return length;
    }

    @Override
    public int read(short[] out, int maxFrames) throws IOException {
        int frameBytes = channels * 2;
        int want = maxFrames * frameBytes;
        if (bytes.length < want) bytes = new byte[want];
        int got = 0;
        while (got < want) {
            int n = in.read(bytes, got, want - got);
            if (n < 0) break;
            got += n;
        }
        int frames = got / frameBytes;
        for (int i = 0; i < frames * channels; i++) {
            out[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
        }
        return frames;
    }

    @Override
    public void seek(long frame) throws IOException {
        in.close();
        in = open();
        long left = Math.max(0, frame) * channels * 2;
        byte[] junk = null;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) { // skip() may refuse; read and throw away instead
                if (junk == null) junk = new byte[1 << 14];
                int n = in.read(junk, 0, (int) Math.min(junk.length, left));
                if (n < 0) return;
                skipped = n;
            }
            left -= skipped;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
