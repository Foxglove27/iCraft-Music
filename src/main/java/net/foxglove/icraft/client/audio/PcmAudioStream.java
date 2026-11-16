package net.foxglove.icraft.client.audio;

import net.foxglove.icraft.client.audio.decode.PcmSource;
import net.minecraft.client.sounds.AudioStream;
import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Feeds a {@link PcmSource} to the game's sound engine, optionally mixing stereo down to mono. */
public class PcmAudioStream implements AudioStream {
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean errorLogged;
    private volatile boolean reachedEnd; // the decoder has no more samples (the last buffers may still be playing)
    private volatile boolean failed;     // reading failed part-way through
    private final PcmSource source;
    private final int channels;     // channels in the file
    private final int outChannels;  // channels we hand to OpenAL
    private final AudioFormat format;
    private short[] scratch = new short[0];

    public PcmAudioStream(PcmSource source, boolean downmixToMono) {
        this.source = source;
        this.channels = source.channels();
        this.outChannels = (downmixToMono && channels == 2) ? 1 : channels;
        this.format = new AudioFormat(source.sampleRate(), 16, outChannels, true, false);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        int frames = Math.max(1, size / (2 * outChannels));
        if (scratch.length < frames * channels) scratch = new short[frames * channels];
        int got;
        try {
            got = source.read(scratch, frames);
        } catch (IOException | RuntimeException e) {
            // the engine only sees a failed stream; make sure the reason reaches the log
            failed = true;
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.warn("Decoding {} failed during playback", source.getClass().getSimpleName(), e);
            }
            throw e;
        }

        ByteBuffer out = BufferUtils.createByteBuffer(got * outChannels * 2).order(ByteOrder.LITTLE_ENDIAN);
        if (outChannels == channels) {
            for (int i = 0; i < got * channels; i++) out.putShort(scratch[i]);
        } else { // stereo -> mono
            for (int i = 0; i < got; i++) out.putShort((short) ((scratch[2 * i] + scratch[2 * i + 1]) / 2));
        }
        out.flip();
        if (got == 0) reachedEnd = true;
        return out; // empty buffer = end of stream
    }

    /** True once the decoder ran out of samples. The sound may still be playing its last buffers. */
    public boolean hasReachedEnd() {
        return reachedEnd;
    }

    /** True if reading the file failed after playback had started. */
    public boolean hasFailed() {
        return failed;
    }

    /** Jumps to a sample frame. Not used by the player yet (planned for relinking and audiobooks). */
    public void seekToFrame(long frame) throws IOException {
        source.seek(frame);
    }

    /** Length in sample frames, or -1 if unknown. */
    public long lengthFrames() {
        return source.lengthFrames();
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
