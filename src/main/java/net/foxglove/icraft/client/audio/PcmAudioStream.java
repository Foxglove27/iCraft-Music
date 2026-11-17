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
    private volatile long startFrame;  // where in the file this stream began (after a seek)
    private volatile long framesRead;  // frames handed to the engine since then
    private volatile long endFrame = -1; // stop here (a chapter inside a longer file), -1 = end of file
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
        if (endFrame >= 0) {
            long left = endFrame - (startFrame + framesRead);
            if (left <= 0) {
                reachedEnd = true;
                return BufferUtils.createByteBuffer(0);
            }
            frames = (int) Math.min(frames, left);
        }
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
        framesRead += got;
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

    /** Jumps to a sample frame of the file. Call before the first read (or when restarting). */
    public void seekToFrame(long frame) throws IOException {
        source.seek(frame);
        startFrame = frame;
        framesRead = 0;
    }

    /** Ends the stream at this frame of the file (used for a chapter inside a longer file). */
    public void limitToFrame(long frame) {
        endFrame = frame;
    }

    /** Sample frames per second of what the engine plays (the file's rate). */
    public int sampleRate() {
        return source.sampleRate();
    }

    /**
     * Where in the file playback is, in frames. {@code queuedBuffers} is how many of our buffers are in
     * the OpenAL queue and {@code sampleOffset} how far into that queue the source has played. The sound
     * engine fills its buffers with one second of audio each.
     */
    public long playedFrames(int queuedBuffers, int sampleOffset) {
        long queuedFrames = (long) queuedBuffers * sampleRate();
        long position = startFrame + framesRead - queuedFrames + sampleOffset;
        return Math.max(startFrame, position);
    }

    /** The frame the stream started at (position before anything has played). */
    public long startFrame() {
        return startFrame;
    }

    /** Length in sample frames, or -1 if unknown. */
    public long lengthFrames() {
        return source.lengthFrames();
    }

    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /** Closes the decoder. Safe to call more than once (the engine and our own clean-up may both do it). */
    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) source.close();
    }
}
