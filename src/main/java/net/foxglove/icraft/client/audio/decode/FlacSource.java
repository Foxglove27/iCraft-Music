package net.foxglove.icraft.client.audio.decode;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A small FLAC decoder written for this mod (no third-party code). Reads from disk as it plays
 * and can seek, using the file's seek table when it has one.
 *
 * Supports 4 to 24 bit, mono or stereo. The CRCs are not checked. Output is cut to 16 bit.
 */
public final class FlacSource extends BlockSource {
    private RandomAccessFile file;
    private Bits in;

    private int sampleRate;
    private int channels;
    private int streamBps;
    private int maxBlock;
    private long totalSamples;
    private long firstFrameOffset;
    private long[] seekSamples = new long[0];
    private long[] seekOffsets = new long[0];

    private int[][] chan = new int[2][0];
    private short[] pcm = new short[0];
    private long lastFrameStart;

    public FlacSource(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r");
        try {
            readHeader();
            in = new Bits(file);
            in.seekTo(firstFrameOffset);
        } catch (EOFException e) {
            file.close();
            throw new IOException("Not a FLAC file (too short)", e);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private void readHeader() throws IOException {
        long pos = 0;
        byte[] marker = new byte[4];
        file.readFully(marker);
        if (marker[0] == 'I' && marker[1] == 'D' && marker[2] == '3') { // an ID3v2 tag in front
            byte[] rest = new byte[6];
            file.readFully(rest);
            int size = (rest[2] & 0x7F) << 21 | (rest[3] & 0x7F) << 14 | (rest[4] & 0x7F) << 7 | (rest[5] & 0x7F);
            pos = 10L + size + ((rest[1] & 0x10) != 0 ? 10 : 0);
            file.seek(pos);
            file.readFully(marker);
        }
        if (!new String(marker, StandardCharsets.ISO_8859_1).equals("fLaC")) {
            throw new IOException("Not a FLAC file");
        }

        boolean gotInfo = false;
        boolean last;
        do {
            int head = file.readUnsignedByte();
            last = (head & 0x80) != 0;
            int type = head & 0x7F;
            int len = file.readUnsignedByte() << 16 | file.readUnsignedByte() << 8 | file.readUnsignedByte();
            if (type == 0 && len >= 34) {
                byte[] b = new byte[len];
                file.readFully(b);
                maxBlock = (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
                long v = 0;
                for (int i = 10; i < 18; i++) v = v << 8 | (b[i] & 0xFF);
                sampleRate = (int) (v >>> 44);
                channels = (int) ((v >>> 41) & 7) + 1;
                streamBps = (int) ((v >>> 36) & 0x1F) + 1;
                totalSamples = v & 0xFFFFFFFFFL;
                gotInfo = true;
            } else if (type == 3) {
                byte[] b = new byte[len];
                file.readFully(b);
                int n = len / 18;
                long[] samples = new long[n];
                long[] offsets = new long[n];
                int count = 0;
                for (int i = 0; i < n; i++) {
                    long s = be64(b, i * 18);
                    if (s == -1L) continue; // placeholder entry
                    samples[count] = s;
                    offsets[count] = be64(b, i * 18 + 8);
                    count++;
                }
                seekSamples = java.util.Arrays.copyOf(samples, count);
                seekOffsets = java.util.Arrays.copyOf(offsets, count);
            } else {
                file.seek(file.getFilePointer() + len);
            }
        } while (!last);

        if (!gotInfo) throw new IOException("FLAC file has no STREAMINFO");
        if (channels > 2) throw new IOException("Only mono or stereo files are supported");
        if (streamBps < 4 || streamBps > 24) throw new IOException("Unsupported FLAC bit depth: " + streamBps);
        if (sampleRate <= 0 || maxBlock <= 0) throw new IOException("Broken FLAC header");
        firstFrameOffset = file.getFilePointer();
    }

    private static long be64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = v << 8 | (b[off + i] & 0xFF);
        return v;
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
        return totalSamples > 0 ? totalSamples : -1;
    }

    @Override
    protected boolean fillBlock() throws IOException {
        try {
            return decodeFrame();
        } catch (EOFException e) {
            return false; // the file ends in the middle of a frame: play what there is
        }
    }

    @Override
    public void seek(long frame) throws IOException {
        frame = Math.max(0, frame);
        long offset = firstFrameOffset;
        for (int i = 0; i < seekSamples.length; i++) {
            if (seekSamples[i] <= frame) offset = firstFrameOffset + seekOffsets[i];
        }
        in.seekTo(offset);
        discardBlock();
        while (fillBlock()) {
            if (lastFrameStart + blockFrames() > frame) {
                skipInBlock((int) Math.max(0, frame - lastFrameStart));
                return;
            }
            discardBlock();
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    // ---- frame decoding ----

    /** Decodes one frame into the block. Returns false at the end of the file. */
    private boolean decodeFrame() throws IOException {
        if (in.atEof()) return false;

        if (in.read(14) != 0x3FFE) throw new IOException("Lost sync in FLAC data");
        in.read(1);
        int strategy = in.read(1);
        int sizeCode = in.read(4);
        int rateCode = in.read(4);
        int assignment = in.read(4);
        int depthCode = in.read(3);
        in.read(1);
        long number = readUtf8();

        int blockSize;
        if (sizeCode == 1) blockSize = 192;
        else if (sizeCode >= 2 && sizeCode <= 5) blockSize = 576 << (sizeCode - 2);
        else if (sizeCode == 6) blockSize = in.read(8) + 1;
        else if (sizeCode == 7) blockSize = in.read(16) + 1;
        else if (sizeCode >= 8) blockSize = 256 << (sizeCode - 8);
        else throw new IOException("Bad FLAC block size");
        if (rateCode == 12) in.read(8);
        else if (rateCode == 13 || rateCode == 14) in.read(16);
        in.read(8); // header CRC, not checked

        int bps = switch (depthCode) {
            case 0 -> streamBps;
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            default -> throw new IOException("Unsupported FLAC bit depth");
        };

        int frameChannels = assignment < 8 ? assignment + 1 : 2;
        if (assignment > 10 || frameChannels != channels) throw new IOException("Bad FLAC channel layout");

        lastFrameStart = strategy == 0 ? number * maxBlock : number;

        for (int c = 0; c < channels; c++) {
            if (chan[c].length < blockSize) chan[c] = new int[blockSize];
            boolean side = (assignment == 8 || assignment == 10) ? c == 1 : (assignment == 9 && c == 0);
            decodeSubframe(chan[c], blockSize, bps + (side ? 1 : 0));
        }
        in.align();
        in.read(16); // frame CRC, not checked

        int[] a = chan[0];
        int[] b = chan[1];
        switch (assignment) {
            case 8 -> { for (int i = 0; i < blockSize; i++) b[i] = a[i] - b[i]; }
            case 9 -> { for (int i = 0; i < blockSize; i++) a[i] = a[i] + b[i]; }
            case 10 -> {
                for (int i = 0; i < blockSize; i++) {
                    int side = b[i];
                    int mid = (a[i] << 1) | (side & 1);
                    a[i] = (mid + side) >> 1;
                    b[i] = (mid - side) >> 1;
                }
            }
            default -> { }
        }

        if (pcm.length < blockSize * channels) pcm = new short[blockSize * channels];
        int shift = bps - 16;
        for (int c = 0; c < channels; c++) {
            int[] src = chan[c];
            for (int i = 0; i < blockSize; i++) {
                int v = shift >= 0 ? src[i] >> shift : src[i] << -shift;
                pcm[i * channels + c] = (short) v;
            }
        }
        setBlock(pcm, blockSize);
        return true;
    }

    private long readUtf8() throws IOException {
        int first = in.read(8);
        if (first < 0x80) return first;
        int n = Integer.numberOfLeadingZeros(~first & 0xFF) - 24; // leading 1 bits
        if (n < 2 || n > 7) throw new IOException("Bad FLAC frame number");
        long v = first & (0x7F >> n);
        for (int i = 1; i < n; i++) {
            int c = in.read(8);
            if ((c & 0xC0) != 0x80) throw new IOException("Bad FLAC frame number");
            v = v << 6 | (c & 0x3F);
        }
        return v;
    }

    private void decodeSubframe(int[] out, int n, int bps) throws IOException {
        if (in.read(1) != 0) throw new IOException("Bad FLAC subframe");
        int type = in.read(6);
        int wasted = 0;
        if (in.read(1) == 1) wasted = in.readUnary() + 1;
        bps -= wasted;
        if (bps < 1 || bps > 25) throw new IOException("Bad FLAC subframe");

        if (type == 0) {
            int v = in.readSigned(bps);
            for (int i = 0; i < n; i++) out[i] = v;
        } else if (type == 1) {
            for (int i = 0; i < n; i++) out[i] = in.readSigned(bps);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            if (order > n) throw new IOException("Bad FLAC subframe");
            for (int i = 0; i < order; i++) out[i] = in.readSigned(bps);
            readResidual(out, n, order);
            switch (order) {
                case 1 -> { for (int i = 1; i < n; i++) out[i] += out[i - 1]; }
                case 2 -> { for (int i = 2; i < n; i++) out[i] += 2 * out[i - 1] - out[i - 2]; }
                case 3 -> { for (int i = 3; i < n; i++) out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3]; }
                case 4 -> {
                    for (int i = 4; i < n; i++) {
                        out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4];
                    }
                }
                default -> { }
            }
        } else if (type >= 32) {
            int order = type - 31;
            if (order > n) throw new IOException("Bad FLAC subframe");
            for (int i = 0; i < order; i++) out[i] = in.readSigned(bps);
            int precision = in.read(4) + 1;
            if (precision == 16) throw new IOException("Bad FLAC subframe");
            int shift = in.readSigned(5);
            if (shift < 0) throw new IOException("Bad FLAC subframe");
            int[] coef = new int[order];
            for (int i = 0; i < order; i++) coef[i] = in.readSigned(precision);
            readResidual(out, n, order);
            for (int i = order; i < n; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += (long) coef[j] * out[i - 1 - j];
                out[i] += (int) (sum >> shift);
            }
        } else {
            throw new IOException("Bad FLAC subframe");
        }

        if (wasted > 0) {
            for (int i = 0; i < n; i++) out[i] <<= wasted;
        }
    }

    /** Reads the Rice-coded residual into out[order..n). */
    private void readResidual(int[] out, int n, int order) throws IOException {
        int method = in.read(2);
        if (method > 1) throw new IOException("Bad FLAC residual");
        int paramBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = in.read(4);
        int partitions = 1 << partitionOrder;
        if ((n & (partitions - 1)) != 0 || (n >> partitionOrder) < order) {
            throw new IOException("Bad FLAC residual");
        }

        int idx = order;
        for (int p = 0; p < partitions; p++) {
            int count = (n >> partitionOrder) - (p == 0 ? order : 0);
            int param = in.read(paramBits);
            if (param == escape) {
                int bits = in.read(5);
                for (int i = 0; i < count; i++) out[idx++] = bits == 0 ? 0 : in.readSigned(bits);
            } else {
                for (int i = 0; i < count; i++) {
                    int q = in.readUnary();
                    int v = (q << param) | (param > 0 ? in.read(param) : 0);
                    out[idx++] = (v >>> 1) ^ -(v & 1);
                }
            }
        }
    }

    // ---- bit reader ----

    /** Reads bits (most significant first) from a file through a buffer. */
    private static final class Bits {
        private final RandomAccessFile file;
        private final byte[] buf = new byte[1 << 16];
        private int len;
        private int pos;
        private long acc;      // only the low accBits bits are meaningful
        private int accBits;

        Bits(RandomAccessFile file) {
            this.file = file;
        }

        void seekTo(long offset) throws IOException {
            file.seek(offset);
            len = 0;
            pos = 0;
            acc = 0;
            accBits = 0;
        }

        private int nextByte() throws IOException {
            if (pos >= len) {
                len = file.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    throw new EOFException("Unexpected end of FLAC data");
                }
            }
            return buf[pos++] & 0xFF;
        }

        boolean atEof() throws IOException {
            if (accBits > 0) return false;
            if (pos < len) return false;
            len = file.read(buf, 0, buf.length);
            pos = 0;
            if (len <= 0) {
                len = 0;
                return true;
            }
            return false;
        }

        /** Reads 0 to 32 unsigned bits. */
        int read(int n) throws IOException {
            while (accBits < n) {
                acc = (acc << 8) | nextByte();
                accBits += 8;
            }
            accBits -= n;
            return (int) ((acc >>> accBits) & ((1L << n) - 1));
        }

        int readSigned(int n) throws IOException {
            int v = read(n);
            return (v << (32 - n)) >> (32 - n);
        }

        /** Counts zero bits up to the next 1 bit (and consumes that 1). */
        int readUnary() throws IOException {
            int count = 0;
            while (true) {
                if (accBits == 0) {
                    acc = nextByte();
                    accBits = 8;
                }
                long v = acc & ((1L << accBits) - 1);
                if (v != 0) {
                    int zeros = Long.numberOfLeadingZeros(v) - (64 - accBits);
                    accBits -= zeros + 1;
                    return count + zeros;
                }
                count += accBits;
                accBits = 0;
            }
        }

        void align() {
            accBits -= accBits % 8;
        }
    }
}
