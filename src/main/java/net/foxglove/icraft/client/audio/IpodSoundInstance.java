package net.foxglove.icraft.client.audio;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;
import net.foxglove.icraft.client.IpodPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.DoubleSupplier;

public class IpodSoundInstance extends AbstractTickableSoundInstance {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Opens the audio of a track. {@code downmixToMono} is true for sounds that come from a speaker. */
    @FunctionalInterface
    public interface Opener {
        PcmAudioStream open(boolean downmixToMono) throws IOException;
    }

    @Nullable
    private final Opener opener;   // null means "a normal sound event" (a music disc from a held iPod)
    private final String label;    // for log messages
    private final boolean positional;
    private final boolean managed; // controlled by IpodPlayer (false for the sounds a dock plays for everyone)
    private final DoubleSupplier startSeconds; // where in the file to begin (0 = the start); read when it opens
    private final double endSeconds;           // where to stop (-1 = the end of the file)
    @Nullable
    private volatile PcmAudioStream stream; // set once a file has been opened; null for discs

    // A vanilla music disc sound
    public static IpodSoundInstance forDisc(SoundEvent sound, @Nullable Vec3 pos, float volume) {
        return new IpodSoundInstance(sound, null, "disc", true, pos, volume, () -> 0, -1);
    }

    /**
     * A sound a dock plays (not controlled by IpodPlayer): a disc decoded by us so it can start anywhere.
     * {@code startSeconds} is asked when the stream opens, so the time spent loading is not lost.
     */
    public static IpodSoundInstance forDock(Opener opener, String label, @Nullable Vec3 pos, float volume, DoubleSupplier startSeconds) {
        return new IpodSoundInstance(borrowedEvent(), opener, label, false, pos, volume, startSeconds, -1);
    }

    // A file from the ipod_music folder
    public static IpodSoundInstance forFile(Path file, @Nullable Vec3 pos, float volume,
                                            double startSeconds, double endSeconds) {
        return new IpodSoundInstance(borrowedEvent(), downmix -> AudioDecoders.open(file, downmix),
                file.toString(), true, pos, volume, () -> startSeconds, endSeconds);
    }

    // borrow a vanilla streamed sound so the engine takes its streaming path;
    // getStream() below swaps in our own audio data
    private static SoundEvent borrowedEvent() {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("music_disc.cat"));
    }

    private IpodSoundInstance(SoundEvent sound, @Nullable Opener opener, String label, boolean managed,
                              @Nullable Vec3 pos, float volume, DoubleSupplier startSeconds, double endSeconds) {
        super(sound, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.opener = opener;
        this.label = label;
        this.managed = managed;
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
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

    /** The decoder stream of a file track once it is open, or null. */
    @Nullable
    public PcmAudioStream stream() {
        return stream;
    }

    /** True for a file from the library folder, false for a vanilla disc. */
    public boolean isFile() {
        return opener != null;
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
        if (!managed) return; // a dock's sound is started and stopped by the dock's own player
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
        if (opener == null) {
            // a normal disc: let the game load its own audio file as usual
            future = super.getStream(soundBuffers, sound, looping);
        } else {
            // a file or a disc that we decode ourselves, streaming
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    PcmAudioStream opened = opener.open(positional);
                    double start = startSeconds.getAsDouble();
                    if (start > 0) {
                        try {
                            opened.seekToFrame(Math.round(start * opened.sampleRate()));
                        } catch (IOException e) {
                            // the position is unusable after a failed seek: start again from the beginning
                            LOGGER.warn("Could not seek to {} s in {}; playing from the start", start, label, e);
                            opened.close();
                            opened = opener.open(positional);
                        }
                    }
                    if (endSeconds >= 0) opened.limitToFrame(Math.round(endSeconds * opened.sampleRate()));
                    if (!managed) {
                        LOGGER.info("Dock sound {} opened: {} Hz, {} s in, {}", label, opened.sampleRate(), start,
                                positional ? "from a speaker" : "in the player's ears");
                    }
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
            if (managed) {
                Minecraft.getInstance().execute(() -> IpodPlayer.onLoadFailed(this, error));
            } else {
                LOGGER.warn("Could not play {} from a dock", label, error.getCause() != null ? error.getCause() : error);
            }
            return new SilentAudioStream();
        });
    }
}
