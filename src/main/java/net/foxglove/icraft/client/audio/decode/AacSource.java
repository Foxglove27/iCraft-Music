package net.foxglove.icraft.client.audio.decode;

import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import java.io.IOException;
import java.util.Arrays;

/**
 * AAC in an MP4 container (.m4a, .m4b). {@link Mp4File} finds the frames, JAAD (public domain,
 * bundled, see THIRD_PARTY.md) decodes them. Streams from disk. Seeking jumps to a frame shortly
 * before the target, decodes up to it and drops the rest.
 */
public final class AacSource extends BlockSource {
    private static final double WARM_UP_SECONDS = 0.1;
    private static final int MAX_BAD_FRAMES = 50;

    private final Mp4File mp4;
    private Decoder decoder;
    private final SampleBuffer buffer = new SampleBuffer();
    private short[] pcm = new short[0];

    private int nextSample;
    private int sampleRate;
    private int channels;
    private int badInARow;
    private int lastFrameLength = 1024;
    private boolean firstPending; // the first block is decoded in the constructor to learn the real format

    /** Takes ownership of {@code mp4} (it is closed if opening fails, and by {@link #close()}). */
    public AacSource(Mp4File mp4) throws IOException {
        this.mp4 = mp4;
        try {
            if (mp4.codec() != Mp4File.Codec.AAC) throw new IOException("Not an AAC track");
            decoder = new Decoder(mp4.specificInfo());
            if (!decodeNext()) throw new IOException("The file contains no audio frames");
            firstPending = true;
            if (channels < 1 || channels > 2) throw new IOException("Only mono or stereo files are supported");
        } catch (AACException e) {
            mp4.close();
            throw new IOException("Unsupported AAC stream: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            mp4.close();
            throw e;
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
        return (long) (mp4.durationSeconds() * sampleRate);
    }

    @Override
    protected boolean fillBlock() throws IOException {
        if (firstPending) {
            firstPending = false;
            return true; // already decoded by the constructor
        }
        return decodeNext();
    }

    /** Decodes the next AAC frame into the block. Returns false at the end of the file. */
    private boolean decodeNext() throws IOException {
        while (nextSample < mp4.sampleCount()) {
            byte[] data = mp4.readSample(nextSample++);
            try {
                buffer.setBigEndian(false);
                decoder.decodeFrame(data, buffer);
            } catch (AACException | RuntimeException e) {
                // a damaged frame becomes silence of the same length, so the timing stays right
                if (++badInARow > MAX_BAD_FRAMES) throw new IOException("Too many damaged AAC frames", e);
                if (channels > 0) {
                    if (pcm.length < lastFrameLength * channels) pcm = new short[lastFrameLength * channels];
                    Arrays.fill(pcm, 0, lastFrameLength * channels, (short) 0);
                    setBlock(pcm, lastFrameLength);
                    return true;
                }
                continue; // damaged before the format is known
            }
            badInARow = 0;

            sampleRate = buffer.getSampleRate();
            channels = buffer.getChannels();
            byte[] bytes = buffer.getData();
            int samples = bytes.length / 2;
            int frames = channels > 0 ? samples / channels : 0;
            if (frames == 0) continue;
            lastFrameLength = frames;
            if (pcm.length < samples) pcm = new short[samples];
            for (int i = 0; i < samples; i++) {
                pcm[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
            }
            setBlock(pcm, frames);
            return true;
        }
        return false;
    }

    @Override
    public void seek(long frame) throws IOException {
        frame = Math.max(0, frame);
        double target = (double) frame / sampleRate;
        int start = mp4.sampleAtTime(Math.max(0, target - WARM_UP_SECONDS));
        nextSample = start;
        try {
            decoder = new Decoder(mp4.specificInfo()); // forget the overlap of the old position
        } catch (AACException e) {
            throw new IOException("Could not restart the AAC decoder", e);
        }
        firstPending = false;
        discardBlock();

        long pos = Math.round(mp4.timeOfSample(start) * sampleRate);
        while (decodeNext()) {
            if (pos + blockFrames() > frame) {
                skipInBlock((int) Math.max(0, frame - pos));
                return;
            }
            pos += blockFrames();
            discardBlock();
        }
    }

    @Override
    public void close() throws IOException {
        mp4.close();
    }
}
