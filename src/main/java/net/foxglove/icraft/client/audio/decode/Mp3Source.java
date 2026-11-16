package net.foxglove.icraft.client.audio.decode;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 through JLayer (LGPL, bundled unmodified, see THIRD_PARTY.md). Streams from disk.
 * Seeking skips frame headers without decoding, then decodes a few frames to warm the decoder up.
 */
public final class Mp3Source extends BlockSource {
    private static final int WARM_UP_FRAMES = 4;
    private static final int MAX_BAD_FRAMES = 50;

    private final Path path;
    private Bitstream bitstream;
    private Decoder decoder;
    private Header pending; // the first header, read early to learn the format

    private final int sampleRate;
    private final int channels;
    private final int samplesPerFrame;
    private final short[] silence;
    private int badInARow;

    public Mp3Source(Path path) throws IOException {
        this.path = path;
        open();
        try {
            pending = bitstream.readFrame();
        } catch (BitstreamException e) {
            close();
            throw wrap(e);
        }
        if (pending == null) {
            close();
            throw new IOException("No MP3 audio found");
        }
        sampleRate = pending.frequency();
        channels = pending.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
        samplesPerFrame = switch (pending.layer()) {
            case 1 -> 384;
            case 2 -> 1152;
            default -> pending.version() == Header.MPEG1 ? 1152 : 576;
        };
        silence = new short[samplesPerFrame * channels];
    }

    private void open() throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(path), 1 << 16);
        bitstream = new Bitstream(raw);
        decoder = new Decoder();
    }

    private static IOException wrap(JavaLayerException e) {
        return e.getCause() instanceof IOException io ? io : new IOException("Could not decode MP3: " + e.getMessage(), e);
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
        return -1; // would need a full scan or a Xing header; not needed yet
    }

    @Override
    protected boolean fillBlock() throws IOException {
        while (true) {
            Header header;
            try {
                header = pending != null ? pending : bitstream.readFrame();
            } catch (BitstreamException e) {
                throw wrap(e);
            }
            pending = null;
            if (header == null) return false;

            try {
                SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                badInARow = 0;
                if (out.getBufferLength() == 0) {
                    // the decoder had no data for this frame yet (needs the bit reservoir of
                    // earlier frames); it still occupies its place on the timeline
                    setBlock(silence, samplesPerFrame);
                } else {
                    setBlock(out.getBuffer(), out.getBufferLength() / channels);
                }
                out.clear_buffer(); // only resets write positions, the samples stay readable
                bitstream.closeFrame();
                return true;
            } catch (DecoderException e) {
                // A damaged frame (or one that needs data from frames we skipped while seeking)
                // becomes silence. It must still count as a frame so the timing stays right.
                bitstream.closeFrame();
                if (++badInARow > MAX_BAD_FRAMES) throw new IOException("Too many damaged MP3 frames", e);
                setBlock(silence, samplesPerFrame);
                return true;
            }
        }
    }

    @Override
    public void seek(long frame) throws IOException {
        frame = Math.max(0, frame);
        closeQuietly();
        open();
        pending = null;
        discardBlock();

        long target = frame / samplesPerFrame;
        long first = Math.max(0, target - WARM_UP_FRAMES);
        try {
            for (long i = 0; i < first; i++) {
                if (bitstream.readFrame() == null) return; // past the end
                bitstream.closeFrame();
            }
        } catch (BitstreamException e) {
            throw wrap(e);
        }

        long pos = first * samplesPerFrame;
        while (fillBlock()) {
            if (pos + blockFrames() > frame) {
                skipInBlock((int) (frame - pos));
                return;
            }
            pos += blockFrames();
            discardBlock();
        }
    }

    private void closeQuietly() {
        try {
            bitstream.close();
        } catch (BitstreamException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (BitstreamException e) {
            throw wrap(e);
        }
    }
}
