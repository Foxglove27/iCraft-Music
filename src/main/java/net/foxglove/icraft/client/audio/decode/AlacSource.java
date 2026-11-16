package net.foxglove.icraft.client.audio.decode;

import net.foxglove.icraft.client.audio.decode.alac.AlacDecodeUtils;
import net.foxglove.icraft.client.audio.decode.alac.AlacFile;

import java.io.IOException;
import java.util.Arrays;

/**
 * Apple Lossless (ALAC) in an MP4 container (.m4a, .m4b). {@link Mp4File} finds the packets, the
 * decoder in the {@code alac} package (BSD licensed, see THIRD_PARTY.md) decodes them. Streams from disk.
 * Every packet has the same length, so seeking is exact and needs no warm-up.
 *
 * Supports 16 and 24 bit, mono or stereo. 24 bit is cut to 16 bit like in {@link FlacSource}.
 */
public final class AlacSource extends BlockSource {
    private final Mp4File mp4;
    private final AlacFile alac;
    private final int sampleRate;
    private final int channels;
    private final int bitDepth;
    private final int packetFrames;

    private int nextPacket;
    private int[] decoded;
    private short[] pcm;

    /** Takes ownership of {@code mp4} (it is closed if opening fails, and by {@link #close()}). */
    public AlacSource(Mp4File mp4) throws IOException {
        this.mp4 = mp4;
        try {
            byte[] cfg = mp4.specificInfo();
            if (mp4.codec() != Mp4File.Codec.ALAC || cfg == null || cfg.length < 24) {
                throw new IOException("Not an Apple Lossless track");
            }
            int frameLength = ((cfg[0] & 0xFF) << 24) | ((cfg[1] & 0xFF) << 16) | ((cfg[2] & 0xFF) << 8) | (cfg[3] & 0xFF);
            bitDepth = cfg[5] & 0xFF;
            channels = cfg[9] & 0xFF;
            sampleRate = ((cfg[20] & 0xFF) << 24) | ((cfg[21] & 0xFF) << 16) | ((cfg[22] & 0xFF) << 8) | (cfg[23] & 0xFF);
            if (channels < 1 || channels > 2) throw new IOException("Only mono or stereo files are supported");
            if (bitDepth != 16 && bitDepth != 24) throw new IOException("Unsupported Apple Lossless bit depth: " + bitDepth);
            if (frameLength <= 0 || frameLength > 16384 || sampleRate <= 0) throw new IOException("Broken Apple Lossless config");
            packetFrames = frameLength;

            alac = AlacDecodeUtils.create_alac(bitDepth, channels);
            AlacDecodeUtils.set_config(alac, cfg);
            decoded = new int[frameLength * channels * (bitDepth == 24 ? 3 : 1) + 8];
            pcm = new short[frameLength * channels];
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
        if (nextPacket >= mp4.sampleCount()) return false;
        // the bit reader looks up to 3 bytes ahead, so give it some zero padding after the packet
        byte[] raw = mp4.readSample(nextPacket++);
        byte[] packet = Arrays.copyOf(raw, raw.length + 8);
        int frames;
        try {
            int bytes = AlacDecodeUtils.decode_frame(alac, packet, decoded, 0);
            frames = bytes / (channels * bitDepth / 8);
            if (frames < 0 || frames > packetFrames) throw new IllegalStateException("bad frame size");
        } catch (RuntimeException e) {
            // a damaged packet becomes silence of one packet, so the timing stays right
            Arrays.fill(pcm, (short) 0);
            setBlock(pcm, packetFrames);
            return true;
        }

        if (bitDepth == 16) {
            for (int i = 0; i < frames * channels; i++) pcm[i] = (short) decoded[i];
        } else { // 24 bit: the decoder gives little-endian bytes; keep the top 16 bits
            for (int i = 0; i < frames * channels; i++) {
                int sample = (decoded[3 * i] & 0xFF) | ((decoded[3 * i + 1] & 0xFF) << 8) | ((decoded[3 * i + 2] & 0xFF) << 16);
                sample = (sample << 8) >> 8; // sign extend 24 bits
                pcm[i] = (short) (sample >> 8);
            }
        }
        setBlock(pcm, frames);
        return true;
    }

    @Override
    public void seek(long frame) throws IOException {
        frame = Math.max(0, frame);
        int packet = mp4.sampleAtTime((double) frame / sampleRate);
        nextPacket = packet;
        discardBlock();
        long pos = Math.round(mp4.timeOfSample(packet) * sampleRate);
        if (!fillBlock()) return;
        skipInBlock((int) Math.max(0, frame - pos));
    }

    @Override
    public void close() throws IOException {
        mp4.close();
    }
}
