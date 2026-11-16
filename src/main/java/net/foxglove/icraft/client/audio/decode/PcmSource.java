package net.foxglove.icraft.client.audio.decode;

import java.io.Closeable;
import java.io.IOException;

/**
 * A decoder that produces 16-bit interleaved PCM. It knows nothing about Minecraft, so it can be
 * tested on its own. One implementation per file format.
 */
public interface PcmSource extends Closeable {
    int sampleRate();

    /** 1 or 2. Anything else is rejected when the source is opened. */
    int channels();

    /** Total length in sample frames, or -1 if the format does not tell us cheaply. */
    long lengthFrames();

    /**
     * Fills {@code out} with up to {@code maxFrames} frames (a frame = one sample per channel).
     * Returns fewer than asked only at the end of the file; 0 means the end.
     */
    int read(short[] out, int maxFrames) throws IOException;

    /** Jumps so that the next {@link #read} starts at (or very close to) this sample frame. */
    void seek(long frame) throws IOException;

    @Override
    void close() throws IOException;
}
