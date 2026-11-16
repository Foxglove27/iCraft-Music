package net.foxglove.icraft.client.audio.decode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small MP4 / M4A / M4B reader: finds the first AAC audio track, gives random access to its
 * frames, and reads chapter markers. It reads only the 'moov' box (the index) into memory and
 * leaves the audio data on disk. No Minecraft imports, so it can be tested on its own.
 *
 * Written by hand because JAAD's own container reader fails on files that carry ordinary tags.
 * JAAD is only used to decode the AAC frames.
 */
public final class Mp4File implements AutoCloseable {
    /** A chapter marker inside the file. */
    public record Marker(String title, double startSeconds) {}

    /** The audio codec of the track. {@link #specificInfo()} holds that codec's config bytes. */
    public enum Codec { AAC, ALAC }

    private static final long MAX_MOOV_BYTES = 512L * 1024 * 1024;

    private final RandomAccessFile file;

    private Codec codec;
    private byte[] specificInfo;        // AAC: the AudioSpecificConfig; ALAC: the 24-byte ALAC config
    private int timescale;
    private double durationSeconds;
    private int[] sampleSizes;          // size of every audio frame
    private long[] chunkOffsets;        // file offset of every chunk
    private int[] chunkFirstSample;     // index of the first frame in every chunk
    private int[] sttsRunStartSample;   // time-to-sample runs
    private long[] sttsRunStartTime;
    private int[] sttsRunDelta;
    private List<Marker> markers = List.of();

    private Mp4File(RandomAccessFile file) {
        this.file = file;
    }

    /** Opens the file and reads its index. Throws if there is no AAC audio track. */
    public static Mp4File open(Path path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
        try {
            Mp4File mp4 = new Mp4File(raf);
            mp4.parse();
            return mp4;
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e instanceof IOException io ? io : new IOException("Could not read MP4 file: " + e, e);
        }
    }

    /** Chapter markers of a file, or an empty list when it has none or cannot be read. */
    public static List<Marker> readMarkers(Path path) {
        try (Mp4File mp4 = open(path)) {
            return mp4.markers;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    public Codec codec() {
        return codec;
    }

    public byte[] specificInfo() {
        return specificInfo;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    public List<Marker> markers() {
        return markers;
    }

    public int sampleCount() {
        return sampleSizes.length;
    }

    /** Start time in seconds of frame {@code index}. */
    public double timeOfSample(int index) {
        int run = runOfSample(index);
        long ticks = sttsRunStartTime[run] + (long) (index - sttsRunStartSample[run]) * sttsRunDelta[run];
        return (double) ticks / timescale;
    }

    /** The frame that contains {@code seconds} (clamped to the file). */
    public int sampleAtTime(double seconds) {
        long ticks = Math.max(0, (long) (seconds * timescale));
        int lo = 0, hi = sttsRunStartTime.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sttsRunStartTime[mid] <= ticks) lo = mid; else hi = mid - 1;
        }
        int delta = Math.max(1, sttsRunDelta[lo]);
        long sample = sttsRunStartSample[lo] + (ticks - sttsRunStartTime[lo]) / delta;
        return (int) Math.min(sample, sampleSizes.length - 1L);
    }

    /** Reads the bytes of one audio frame from disk. */
    public byte[] readSample(int index) throws IOException {
        byte[] data = new byte[sampleSizes[index]];
        file.seek(offsetOfSample(index));
        file.readFully(data);
        return data;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private int runOfSample(int index) {
        int lo = 0, hi = sttsRunStartSample.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sttsRunStartSample[mid] <= index) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private long offsetOfSample(int index) {
        int lo = 0, hi = chunkFirstSample.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (chunkFirstSample[mid] <= index) lo = mid; else hi = mid - 1;
        }
        long offset = chunkOffsets[lo];
        for (int i = chunkFirstSample[lo]; i < index; i++) offset += sampleSizes[i];
        return offset;
    }

    // ---------------------------------------------------------------- parsing

    private void parse() throws IOException {
        ByteBuffer moov = readMoov();
        if (moov == null) throw new IOException("No 'moov' box: not an MP4 file");

        Track audio = null;
        List<Track> all = new ArrayList<>();
        for (Box trak : children(moov, 0, moov.limit())) {
            if (!trak.type.equals("trak")) continue;
            Track t = parseTrack(moov, trak);
            if (t != null) all.add(t);
        }
        String unsupported = null;
        for (Track t : all) {
            if (!t.handler.equals("soun")) continue;
            if (t.specificInfo != null) {
                audio = t;
                break;
            }
            if (unsupported == null) unsupported = t.codecName;
        }
        if (audio == null) {
            throw new IOException(unsupported != null ? "Unsupported audio codec: " + unsupported
                    : "No audio track found");
        }
        if (audio.sampleSizes.length == 0) throw new IOException("Fragmented MP4 files are not supported");

        codec = audio.codec;
        specificInfo = audio.specificInfo;
        timescale = audio.timescale;
        sampleSizes = audio.sampleSizes;
        chunkOffsets = audio.chunkOffsets;
        chunkFirstSample = audio.chunkFirstSample;
        sttsRunStartSample = audio.sttsRunStartSample;
        sttsRunStartTime = audio.sttsRunStartTime;
        sttsRunDelta = audio.sttsRunDelta;
        long totalTicks = sttsRunStartTime[sttsRunStartTime.length - 1]
                + (long) (sampleSizes.length - sttsRunStartSample[sttsRunStartSample.length - 1])
                * sttsRunDelta[sttsRunDelta.length - 1];
        durationSeconds = (double) totalTicks / timescale;

        markers = readChapterMarkers(moov, all, audio);
    }

    /** Finds the 'moov' box in the file and reads just that box. */
    private ByteBuffer readMoov() throws IOException {
        long pos = 0;
        long length = file.length();
        byte[] header = new byte[16];
        while (pos + 8 <= length) {
            file.seek(pos);
            file.readFully(header, 0, 8);
            long size = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            long headerSize = 8;
            if (size == 1) {
                file.readFully(header, 8, 8);
                size = 0;
                for (int i = 8; i < 16; i++) size = (size << 8) | (header[i] & 0xFF);
                headerSize = 16;
            } else if (size == 0) {
                size = length - pos;
            }
            if (size < headerSize) throw new IOException("Broken MP4 box");
            if (type.equals("moov")) {
                long body = size - headerSize;
                if (body > MAX_MOOV_BYTES) throw new IOException("The MP4 index is too large");
                byte[] data = new byte[(int) body];
                file.seek(pos + headerSize);
                file.readFully(data);
                return ByteBuffer.wrap(data);
            }
            pos += size;
        }
        return null;
    }

    private record Box(String type, int start, int end) {}  // start/end = the box body inside the buffer

    private static List<Box> children(ByteBuffer b, int start, int end) {
        List<Box> list = new ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            long size = b.getInt(pos) & 0xFFFFFFFFL;
            String type = fourCC(b, pos + 4);
            int header = 8;
            if (size == 1 && pos + 16 <= end) {
                size = b.getLong(pos + 8);
                header = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < header || pos + size > end) break; // damaged: stop here, keep what we found
            list.add(new Box(type, pos + header, (int) (pos + size)));
            pos += (int) size;
        }
        return list;
    }

    private static String fourCC(ByteBuffer b, int pos) {
        byte[] t = new byte[4];
        for (int i = 0; i < 4; i++) t[i] = b.get(pos + i);
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    private static Box find(ByteBuffer b, Box parent, String type) {
        for (Box c : children(b, parent.start, parent.end)) if (c.type.equals(type)) return c;
        return null;
    }

    private static Box path(ByteBuffer b, Box parent, String... types) {
        Box cur = parent;
        for (String t : types) {
            if (cur == null) return null;
            cur = find(b, cur, t);
        }
        return cur;
    }

    private static final class Track {
        int id;
        String handler = "";
        int timescale;
        int[] chapterTrackIds = new int[0];
        // audio
        Codec codec;
        String codecName = "unknown";
        byte[] specificInfo;
        // sample tables (all tracks)
        int[] sampleSizes = new int[0];
        long[] chunkOffsets = new long[0];
        int[] chunkFirstSample = new int[0];
        int[] sttsRunStartSample = new int[]{0};
        long[] sttsRunStartTime = new long[]{0};
        int[] sttsRunDelta = new int[]{1};
    }

    private Track parseTrack(ByteBuffer b, Box trak) throws IOException {
        Track t = new Track();
        Box tkhd = find(b, trak, "tkhd");
        if (tkhd != null) {
            int version = b.get(tkhd.start) & 0xFF;
            t.id = b.getInt(tkhd.start + (version == 1 ? 20 : 12));
        }
        Box mdia = find(b, trak, "mdia");
        if (mdia == null) return null;
        Box hdlr = find(b, mdia, "hdlr");
        if (hdlr != null) t.handler = fourCC(b, hdlr.start + 8);
        Box mdhd = find(b, mdia, "mdhd");
        if (mdhd != null) {
            int version = b.get(mdhd.start) & 0xFF;
            t.timescale = b.getInt(mdhd.start + (version == 1 ? 20 : 12));
        }
        if (t.timescale <= 0) return null;

        Box chap = path(b, trak, "tref", "chap");
        if (chap != null) {
            int n = (chap.end - chap.start) / 4;
            t.chapterTrackIds = new int[n];
            for (int i = 0; i < n; i++) t.chapterTrackIds[i] = b.getInt(chap.start + 4 * i);
        }

        Box stbl = path(b, mdia, "minf", "stbl");
        if (stbl == null) return t;

        Box stsz = find(b, stbl, "stsz");
        if (stsz != null) {
            int fixed = b.getInt(stsz.start + 4);
            int count = b.getInt(stsz.start + 8);
            if (count < 0 || count > 200_000_000) throw new IOException("Broken MP4 sample table");
            t.sampleSizes = new int[count];
            if (fixed != 0) Arrays.fill(t.sampleSizes, fixed);
            else for (int i = 0; i < count; i++) t.sampleSizes[i] = b.getInt(stsz.start + 12 + 4 * i);
        }

        Box stco = find(b, stbl, "stco");
        Box co64 = find(b, stbl, "co64");
        if (stco != null) {
            int n = b.getInt(stco.start + 4);
            t.chunkOffsets = new long[n];
            for (int i = 0; i < n; i++) t.chunkOffsets[i] = b.getInt(stco.start + 8 + 4 * i) & 0xFFFFFFFFL;
        } else if (co64 != null) {
            int n = b.getInt(co64.start + 4);
            t.chunkOffsets = new long[n];
            for (int i = 0; i < n; i++) t.chunkOffsets[i] = b.getLong(co64.start + 8 + 8 * i);
        }

        Box stsc = find(b, stbl, "stsc");
        if (stsc != null && t.chunkOffsets.length > 0) {
            int entries = b.getInt(stsc.start + 4);
            int chunks = t.chunkOffsets.length;
            t.chunkFirstSample = new int[chunks];
            int sample = 0;
            for (int e = 0; e < entries; e++) {
                int firstChunk = b.getInt(stsc.start + 8 + 12 * e) - 1;
                int perChunk = b.getInt(stsc.start + 8 + 12 * e + 4);
                int nextFirst = e + 1 < entries ? b.getInt(stsc.start + 8 + 12 * (e + 1)) - 1 : chunks;
                for (int c = Math.max(0, firstChunk); c < Math.min(nextFirst, chunks); c++) {
                    t.chunkFirstSample[c] = sample;
                    sample += perChunk;
                }
            }
        }

        Box stts = find(b, stbl, "stts");
        if (stts != null) {
            int n = b.getInt(stts.start + 4);
            if (n > 0) {
                t.sttsRunStartSample = new int[n];
                t.sttsRunStartTime = new long[n];
                t.sttsRunDelta = new int[n];
                int sample = 0;
                long time = 0;
                for (int i = 0; i < n; i++) {
                    int count = b.getInt(stts.start + 8 + 8 * i);
                    int delta = Math.max(1, b.getInt(stts.start + 8 + 8 * i + 4));
                    t.sttsRunStartSample[i] = sample;
                    t.sttsRunStartTime[i] = time;
                    t.sttsRunDelta[i] = delta;
                    sample += count;
                    time += (long) count * delta;
                }
            }
        }

        Box stsd = find(b, stbl, "stsd");
        if (stsd != null && t.handler.equals("soun")) {
            readAudioEntry(b, stsd, t);
        }
        return t;
    }

    /**
     * Reads the audio sample entry: an 'mp4a' entry holds the AAC AudioSpecificConfig in its 'esds' box,
     * an 'alac' entry holds the 24-byte ALAC config in a child box 'alac'. Anything else is left as an
     * unsupported codec (its name is kept for the error message).
     */
    private static void readAudioEntry(ByteBuffer b, Box stsd, Track t) {
        int entry = stsd.start + 8;                      // version/flags + entry count
        if (entry + 36 > stsd.end) return;
        String codec = fourCC(b, entry + 4);
        t.codecName = codec;
        if (!codec.equals("mp4a") && !codec.equals("alac")) return; // ac-3, ... are not supported
        int body = entry + 8;
        int version = b.getShort(body + 8) & 0xFFFF;      // QuickTime sound description version
        int fixed = 28 + (version == 1 ? 16 : version == 2 ? 36 : 0);
        int entryEnd = (int) Math.min(stsd.end, entry + (b.getInt(entry) & 0xFFFFFFFFL));
        for (Box child : children(b, body + fixed, entryEnd)) {
            if (codec.equals("mp4a") && child.type.equals("esds")) {
                byte[] info = parseDescriptors(b, child.start + 4, child.end);
                if (info != null) {
                    t.codec = Codec.AAC;
                    t.specificInfo = info;
                }
                return;
            }
            if (codec.equals("alac") && child.type.equals("alac") && child.end - child.start >= 28) {
                byte[] cfg = new byte[24];                // after the 4 bytes of version and flags
                for (int i = 0; i < 24; i++) cfg[i] = b.get(child.start + 4 + i);
                t.codec = Codec.ALAC;
                t.specificInfo = cfg;
                return;
            }
        }
    }

    // ES_Descriptor (0x03) > DecoderConfigDescriptor (0x04) > DecoderSpecificInfo (0x05)
    private static byte[] parseDescriptors(ByteBuffer b, int pos, int end) {
        while (pos < end) {
            int tag = b.get(pos++) & 0xFF;
            int len = 0;
            for (int i = 0; i < 4 && pos < end; i++) {
                int x = b.get(pos++) & 0xFF;
                len = (len << 7) | (x & 0x7F);
                if ((x & 0x80) == 0) break;
            }
            int contentEnd = Math.min(end, pos + len);
            if (tag == 0x05) {
                byte[] info = new byte[contentEnd - pos];
                for (int i = 0; i < info.length; i++) info[i] = b.get(pos + i);
                return info;
            } else if (tag == 0x03) {
                int p = pos + 2;                          // ES_ID
                int flags = b.get(p++) & 0xFF;
                if ((flags & 0x80) != 0) p += 2;
                if ((flags & 0x40) != 0) p += 1 + (b.get(p) & 0xFF);
                if ((flags & 0x20) != 0) p += 2;
                byte[] r = parseDescriptors(b, p, contentEnd);
                if (r != null) return r;
            } else if (tag == 0x04) {
                byte[] r = parseDescriptors(b, pos + 13, contentEnd);
                if (r != null) return r;
            }
            pos = contentEnd;
        }
        return null;
    }

    // ---------------------------------------------------------------- chapters

    private List<Marker> readChapterMarkers(ByteBuffer moov, List<Track> all, Track audio) throws IOException {
        // 1) Nero-style 'chpl' box in moov > udta
        for (Box box : children(moov, 0, moov.limit())) {
            if (!box.type.equals("udta")) continue;
            Box chpl = find(moov, box, "chpl");
            if (chpl != null) {
                List<Marker> m = parseChpl(moov, chpl);
                if (!m.isEmpty()) return m;
            }
        }
        // 2) QuickTime-style chapter track: a text track the audio track points to with tref > chap
        for (int id : audio.chapterTrackIds) {
            for (Track t : all) {
                if (t.id == id && t.sampleSizes.length > 0) {
                    List<Marker> m = readTextTrack(t);
                    if (!m.isEmpty()) return m;
                }
            }
        }
        return List.of();
    }

    private static List<Marker> parseChpl(ByteBuffer b, Box chpl) {
        int pos = chpl.start;
        int version = b.get(pos) & 0xFF;
        pos += 4;                                          // version + flags
        if (version == 1) pos += 4;                        // reserved
        int count = b.get(pos++) & 0xFF;
        List<Marker> list = new ArrayList<>();
        for (int i = 0; i < count && pos + 9 <= chpl.end; i++) {
            long start = b.getLong(pos);                   // units of 100 ns
            int len = b.get(pos + 8) & 0xFF;
            pos += 9;
            if (pos + len > chpl.end) break;
            byte[] text = new byte[len];
            for (int k = 0; k < len; k++) text[k] = b.get(pos + k);
            pos += len;
            list.add(new Marker(new String(text, StandardCharsets.UTF_8), start / 1e7));
        }
        return list;
    }

    private List<Marker> readTextTrack(Track t) throws IOException {
        List<Marker> list = new ArrayList<>();
        for (int i = 0; i < t.sampleSizes.length; i++) {
            int run = 0;
            while (run + 1 < t.sttsRunStartSample.length && t.sttsRunStartSample[run + 1] <= i) run++;
            long ticks = t.sttsRunStartTime[run] + (long) (i - t.sttsRunStartSample[run]) * t.sttsRunDelta[run];

            int lo = 0, hi = t.chunkFirstSample.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (t.chunkFirstSample[mid] <= i) lo = mid; else hi = mid - 1;
            }
            long offset = t.chunkOffsets[lo];
            for (int k = t.chunkFirstSample[lo]; k < i; k++) offset += t.sampleSizes[k];

            int size = t.sampleSizes[i];
            if (size < 2) continue;
            byte[] data = new byte[size];
            file.seek(offset);
            file.readFully(data);
            int len = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            if (len > size - 2) len = size - 2;
            list.add(new Marker(new String(data, 2, len, StandardCharsets.UTF_8), (double) ticks / t.timescale));
        }
        return list;
    }
}
