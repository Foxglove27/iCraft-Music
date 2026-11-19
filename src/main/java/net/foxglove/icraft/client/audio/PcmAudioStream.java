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

/** Feeds a {@link PcmSource} to the game's sound engine, as stereo or as one mono channel (see {@link ChannelMode}). */
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
    private final ChannelMode mode;
    private final AudioFormat format;
    private short[] scratch = new short[0];
    @org.jetbrains.annotations.Nullable
    private net.foxglove.icraft.client.audio.decode.BassFilter bass; // null = no bass boost
    // the first seconds, decoded in advance by preload(); handed out before anything new is decoded
    private final java.util.Queue<ByteBuffer> preloaded = new java.util.concurrent.ConcurrentLinkedQueue<>();
    @org.jetbrains.annotations.Nullable
    private volatile IOException preloadError; // reading failed while preloading: thrown when the engine gets there

    public PcmAudioStream(PcmSource source, ChannelMode mode) {
        this.source = source;
        this.mode = mode;
        this.channels = source.channels();
        this.outChannels = (mode.isMono() && channels == 2) ? 1 : channels;
        this.format = new AudioFormat(source.sampleRate(), 16, outChannels, true, false);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        ByteBuffer ready = preloaded.poll();
        if (ready != null) return ready;
        IOException error = preloadError;
        if (error != null) {
            preloadError = null;
            throw error;
        }
        return decode(size);
    }

    /**
     * Decodes the first {@code seconds} of audio now (call on a background thread, after the seek). The game's sound
     * engine reads the first 4 seconds of a stream on its own sound thread right before it starts the sound; with
     * several speakers that means each speaker starts only after the one before it has decoded its 4 seconds, so
     * they are milliseconds apart and sound thin. Decoded in advance, those reads only copy memory.
     */
    public void preload(int seconds) {
        int size = sampleRate() * outChannels * 2; // one second, the same size the engine asks for
        try {
            for (int i = 0; i < seconds; i++) {
                ByteBuffer chunk = decode(size);
                preloaded.add(chunk);
                if (!chunk.hasRemaining()) break; // the end of the file
            }
        } catch (IOException e) {
            preloadError = e; // already logged by decode
        } catch (RuntimeException e) {
            preloadError = new IOException(e);
        }
    }

    private ByteBuffer decode(int size) throws IOException {
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
        if (bass != null && got > 0) bass.process(scratch, got); // before picking a channel, so every speaker gets it

        ByteBuffer out = BufferUtils.createByteBuffer(got * outChannels * 2).order(ByteOrder.LITTLE_ENDIAN);
        if (outChannels == channels) {
            for (int i = 0; i < got * channels; i++) out.putShort(scratch[i]);
        } else if (mode == ChannelMode.LEFT) { // stereo -> its left side
            for (int i = 0; i < got; i++) out.putShort(scratch[2 * i]);
        } else if (mode == ChannelMode.RIGHT) { // stereo -> its right side
            for (int i = 0; i < got; i++) out.putShort(scratch[2 * i + 1]);
        } else { // stereo -> mono mix
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

    /** Turns up the bass by {@code db} decibels (0 = off). Call before the first read. */
    public void setBassBoost(double db) {
        bass = db > 0 ? new net.foxglove.icraft.client.audio.decode.BassFilter(db, source.sampleRate(), channels) : null;
    }

    /** Jumps to a sample frame of the file. Call before the first read (or when restarting). */
    public void seekToFrame(long frame) throws IOException {
        source.seek(frame);
        if (bass != null) bass.reset();
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
