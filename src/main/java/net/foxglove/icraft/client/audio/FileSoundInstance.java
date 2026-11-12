package net.foxglove.icraft.client.audio;

import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class FileSoundInstance extends AbstractSoundInstance {
    private final Path file;
    private final boolean positional;

    // pos == null: plays directly on the player. Otherwise plays from that position.
    public FileSoundInstance(Path file, @Nullable Vec3 pos, float volume) {
        // Borrows a vanilla streamed sound so the engine takes its streaming path.
        // getStream() below swaps in our own audio data.
        super(ResourceLocation.withDefaultNamespace("music_disc.cat"), SoundSource.RECORDS,
                SoundInstance.createUnseededRandom());
        this.file = file;
        this.positional = pos != null;

        if (pos != null) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.volume = volume;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.relative = false;
        } else {
            this.volume = 1.0F;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = true;
        }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playable = AudioConverter.toOgg(file);
                return new StbOggStream(playable, positional);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }
}