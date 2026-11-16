package net.foxglove.icraft.client.audio;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/**
 * A few milliseconds of silence, handed to the sound engine when a track could not be loaded.
 *
 * Why not just fail the stream? When the stream future fails, the engine never plays the sound and
 * so never releases the streaming channel it reserved for it. There are only 8 of those, so a few
 * broken files would block all streamed sound ("Maximum sound pool size 8 reached"). Playing and
 * finishing a tiny silent stream lets the engine clean up as it does for every normal track.
 */
final class SilentAudioStream implements AudioStream {
    private static final int FRAMES = 2048;
    private boolean sent;

    @Override
    public AudioFormat getFormat() {
        return new AudioFormat(44100, 16, 1, true, false);
    }

    @Override
    public ByteBuffer read(int size) {
        if (sent) return BufferUtils.createByteBuffer(0); // end of stream
        sent = true;
        return BufferUtils.createByteBuffer(FRAMES * 2); // zero-filled
    }

    @Override
    public void close() {
    }
}
