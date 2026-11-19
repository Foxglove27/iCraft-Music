package net.foxglove.icraft.client.audio;

import net.foxglove.icraft.client.audio.decode.OggSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Plays music discs through our own decoder, so a disc can start in the middle (a player who walks up to a
 * dock that is already playing) and be skipped through. The audio file is read from the game's resources, so
 * resource packs and discs from other mods work too.
 */
public final class DiscAudio {
    private DiscAudio() {}

    /** Finds the audio file of a disc's sound. Call on the main thread. Null if the game has no such sound. */
    @Nullable
    public static ResourceLocation resolve(SoundEvent event) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        WeighedSoundEvents events = sounds.getSoundEvent(event.getLocation());
        if (events == null) return null;
        Sound sound = events.getSound(RandomSource.create());
        if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) return null;
        return sound.getPath();
    }

    /** Opens the audio file found by {@link #resolve}. Can run on any thread. */
    public static PcmAudioStream open(ResourceLocation path, ChannelMode mode) throws IOException {
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(path)
                .orElseThrow(() -> new IOException("Sound file not found: " + path));
        byte[] data;
        try (InputStream in = resource.open()) {
            data = in.readAllBytes();
        }
        ByteBuffer direct = BufferUtils.createByteBuffer(data.length);
        direct.put(data).flip();
        return new PcmAudioStream(new OggSource(direct, path.toString()), mode);
    }
}
