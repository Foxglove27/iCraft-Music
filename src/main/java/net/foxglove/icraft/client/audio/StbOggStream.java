package net.foxglove.icraft.client.audio;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

// Streams an .ogg file from disk, decoding a chunk at a time
public class StbOggStream implements AudioStream {
    private final ByteBuffer encoded; // must stay alive while the decoder is open
    private final long handle;
    private final int channels;       // channels in the file
    private final int outChannels;    // channels we hand to OpenAL
    private final AudioFormat format;

    public StbOggStream(Path file, boolean downmixToMono) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        encoded = BufferUtils.createByteBuffer(bytes.length);
        encoded.put(bytes).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(encoded, error, null);
            if (handle == 0L) {
                throw new IOException("Could not read " + file.getFileName()
                        + " as Ogg Vorbis (error " + error.get(0) + ")");
            }

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            channels = info.channels();
            if (channels < 1 || channels > 2) {
                STBVorbis.stb_vorbis_close(handle);
                throw new IOException("Only mono or stereo files are supported");
            }
            outChannels = (downmixToMono && channels == 2) ? 1 : channels;
            format = new AudioFormat(info.sample_rate(), 16, outChannels, true, false);
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        int frames = size / (2 * outChannels);
        ShortBuffer decoded = BufferUtils.createShortBuffer(frames * channels);
        int got = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, decoded);

        ByteBuffer out = BufferUtils.createByteBuffer(got * outChannels * 2).order(ByteOrder.LITTLE_ENDIAN);
        if (outChannels == channels) {
            for (int i = 0; i < got * channels; i++) {
                out.putShort(decoded.get(i));
            }
        } else { // stereo -> mono
            for (int i = 0; i < got; i++) {
                out.putShort((short) ((decoded.get(2 * i) + decoded.get(2 * i + 1)) / 2));
            }
        }
        out.flip();
        return out; // empty buffer = end of stream
    }

    @Override
    public void close() {
        STBVorbis.stb_vorbis_close(handle);
    }
}