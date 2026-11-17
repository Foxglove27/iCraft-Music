package net.foxglove.icraft.client.audio.decode;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Ogg Vorbis through LWJGL's stb_vorbis. Files up to {@link #READ_LIMIT} are read into a direct buffer in
 * chunks (so a network share that disappears mid-song fails as an ordinary read error, not a crash of the
 * game); bigger files are memory-mapped, so only the parts being played are loaded (limit 2 GiB).
 */
public final class OggSource implements PcmSource {
    /** Files up to this size are copied into memory instead of being mapped. */
    public static final long READ_LIMIT = 256L * 1024 * 1024;

    private ByteBuffer mapped;
    private final boolean isMapped; // a mapped file is released when closed; a buffer from memory is left to the GC
    private final long handle;
    private final int sampleRate;
    private final int channels;
    private final long length;
    private ShortBuffer scratch;
    private boolean closed;

    public OggSource(Path path) throws IOException {
        this(load(path), isMappedSize(path), path.getFileName().toString());
    }

    private static boolean isMappedSize(Path path) throws IOException {
        return java.nio.file.Files.size(path) > READ_LIMIT;
    }

    // small files: read in chunks (safe on network shares); big files: map
    private static ByteBuffer load(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) throw new IOException("File is empty");
            if (size > READ_LIMIT) return mapFile(path);
            ByteBuffer buffer = BufferUtils.createByteBuffer((int) size);
            while (buffer.hasRemaining()) {
                if (ch.read(buffer) < 0) throw new IOException("The file ended early");
            }
            buffer.flip();
            return buffer;
        }
    }

    /** Decodes an Ogg Vorbis file that is already in memory ({@code data} must be a direct buffer). */
    public OggSource(ByteBuffer data, String name) throws IOException {
        this(data, false, name);
    }

    private static ByteBuffer mapFile(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) throw new IOException("File is empty");
            if (size > Integer.MAX_VALUE) throw new IOException("File is larger than 2 GiB");
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, size); // stays valid after the channel closes
        }
    }

    private OggSource(ByteBuffer data, boolean isMapped, String name) throws IOException {
        mapped = data;
        this.isMapped = isMapped;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(mapped, error, null);
            if (handle == 0L) {
                if (isMapped) unmap(mapped);
                throw new IOException("Could not read " + name
                        + " as Ogg Vorbis (error " + error.get(0) + ")");
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            channels = info.channels();
            sampleRate = info.sample_rate();
            if (channels < 1 || channels > 2) {
                STBVorbis.stb_vorbis_close(handle);
                if (isMapped) unmap(mapped);
                throw new IOException("Only mono or stereo files are supported");
            }
        }
        length = Integer.toUnsignedLong(STBVorbis.stb_vorbis_stream_length_in_samples(handle));
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
    public synchronized int read(short[] out, int maxFrames) {
        if (closed) return 0;
        if (scratch == null || scratch.capacity() < maxFrames * channels) {
            scratch = BufferUtils.createShortBuffer(maxFrames * channels);
        }
        int total = 0;
        while (total < maxFrames) {
            scratch.clear();
            scratch.limit((maxFrames - total) * channels);
            int got = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, scratch);
            if (got <= 0) break;
            scratch.limit(got * channels);
            scratch.get(out, total * channels, got * channels);
            total += got;
        }
        return total;
    }

    @Override
    public synchronized void seek(long frame) throws IOException {
        if (closed) return;
        int target = (int) Math.min(Math.max(0, frame), Integer.MAX_VALUE);
        boolean pastEnd = length > 0 && target >= length;
        if (pastEnd) target = (int) length - 1; // stb refuses to seek to or past the end
        if (!STBVorbis.stb_vorbis_seek(handle, target)) {
            throw new IOException("Could not seek in Ogg Vorbis file");
        }
        if (pastEnd) { // step over the last sample so the next read reports the end
            scratch = scratch != null ? scratch : BufferUtils.createShortBuffer(2 * channels);
            scratch.clear();
            scratch.limit(channels);
            STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, scratch);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        STBVorbis.stb_vorbis_close(handle);
        if (isMapped) unmap(mapped); // releases the file right away (matters on Windows)
        mapped = null;
    }

    /** Releases a mapped file now instead of waiting for the garbage collector. Best effort. */
    private static void unmap(ByteBuffer buffer) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafeClass.getMethod("invokeCleaner", ByteBuffer.class).invoke(field.get(null), buffer);
        } catch (Throwable ignored) {
            // the garbage collector will release it later
        }
    }
}
