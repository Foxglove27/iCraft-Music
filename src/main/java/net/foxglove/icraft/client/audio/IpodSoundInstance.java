package net.foxglove.icraft.client.audio;

import net.foxglove.icraft.client.IpodVolume;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.foxglove.icraft.client.IpodPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class IpodSoundInstance extends AbstractTickableSoundInstance {
    @Nullable
    private final Path file;      // null means "a normal sound event" (a music disc)
    private final boolean positional;
    @Nullable
    private volatile PcmAudioStream stream; // set once a file has been opened; null for discs

    // A vanilla music disc sound
    public static IpodSoundInstance forDisc(SoundEvent sound, @Nullable Vec3 pos, float volume) {
        return new IpodSoundInstance(sound, null, pos, volume);
    }

    // A file from the ipod_music folder
    public static IpodSoundInstance forFile(Path file, @Nullable Vec3 pos, float volume) {
        // borrow a vanilla streamed sound so the engine takes its streaming path;
        // getStream() below swaps in our own audio data
        SoundEvent borrowed = SoundEvent.createVariableRangeEvent(
                ResourceLocation.withDefaultNamespace("music_disc.cat"));
        return new IpodSoundInstance(borrowed, file, pos, volume);
    }

    private IpodSoundInstance(SoundEvent sound, @Nullable Path file, @Nullable Vec3 pos, float volume) {
        super(sound, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.file = file;
        this.positional = pos != null;

        if (pos != null) {
            // coming from a speaker block: fixed position, fades with distance
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.volume = volume;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.relative = false;
        } else {
            // no speaker: plays directly on the player, no distance fade
            this.volume = 1.0F;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = true;
        }
    }

    /** True for a file from the ipod_music folder, false for a vanilla disc. */
    public boolean isFile() {
        return file != null;
    }

    /** A file track whose decoder has run out of samples. */
    public boolean streamEnded() {
        PcmAudioStream s = stream;
        return s != null && s.hasReachedEnd();
    }

    /** A file track whose decoding failed during playback. */
    public boolean streamFailed() {
        PcmAudioStream s = stream;
        return s != null && s.hasFailed();
    }

    // The game calls this every tick while the sound exists.
    // We use it so the sound checks for itself whether the iPod is still with the player.
    @Override
    public void tick() {
        if (!IpodPlayer.isCurrent(this)) {
            // This sound was replaced or stopped while it was still loading.
            // Without this check it could start playing later with nothing controlling it.
            stop();
            return;
        }
        IpodPlayer.tick(this);
    }

    @Override
    public float getVolume() {
        return super.getVolume() * IpodVolume.get();
    }
    // Without this, the game refuses to start a sound whose volume is 0
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        CompletableFuture<AudioStream> future;
        if (file == null) {
            // a normal disc: let the game load its own audio file as usual
            future = super.getStream(soundBuffers, sound, looping);
        } else {
            // a file from disk: decode it ourselves, streaming
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    PcmAudioStream opened = AudioDecoders.open(file, positional);
                    stream = opened;
                    return opened;
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, Util.backgroundExecutor());
        }

        // If loading fails, tell the player (back on the main thread) and give the engine a short
        // silent stream instead of a failed one, so it releases the sound channel (see SilentAudioStream)
        return future.handle((stream, error) -> {
            if (error == null) return stream;
            Minecraft.getInstance().execute(() -> IpodPlayer.onLoadFailed(this, error));
            return new SilentAudioStream();
        });
    }
}
