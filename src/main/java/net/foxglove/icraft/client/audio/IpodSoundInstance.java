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
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import net.foxglove.icraft.client.IpodPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

public class IpodSoundInstance extends AbstractTickableSoundInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The engine queues 4 buffers of 1 s when it starts a stream (Channel.attachBufferStream). */
    private static final int PRELOAD_SECONDS = 4;

    /** Opens the audio of a track with the given channels (a speaker gets one mono channel). */
    @FunctionalInterface
    public interface Opener {
        PcmAudioStream open(ChannelMode mode) throws IOException;
    }

    @Nullable
    private final Opener opener;   // null means "a normal sound event" (a music disc from a held iPod)
    private final String label;    // for log messages
    private final boolean positional;
    private final ChannelMode channel; // STEREO in the player's ears, a mono mode from a speaker
    private final boolean managed; // controlled by IpodPlayer (false for the sounds a dock plays for everyone)
    private final DoubleSupplier startSeconds; // where in the file to begin (0 = the start); read when it opens
    private final double endSeconds;           // where to stop (-1 = the end of the file)
    @Nullable
    private volatile PcmAudioStream stream; // set once a file has been opened; null for discs
    private volatile boolean released;      // stopped by us: a stream that finishes opening later is closed at once
    @Nullable
    private volatile StartGroup group;      // the sounds of one track on several speakers that start together
    private volatile boolean enginePlayed;  // the sound engine has started this sound's OpenAL source

    /** Waits until a number of sounds have all arrived (at most a second, so a stuck one cannot hold up the rest). */
    private static final class Barrier {
        private final int size;
        private final AtomicInteger arrived = new AtomicInteger();
        private final CompletableFuture<Void> all = new CompletableFuture<>();

        Barrier(int size) {
            this.size = size;
        }

        void arrive() {
            if (arrived.incrementAndGet() >= size) all.complete(null);
        }

        /** Completes when everybody arrived, or after one second. Never fails. */
        CompletableFuture<Void> await() {
            return all.copy().orTimeout(1, TimeUnit.SECONDS).exceptionally(e -> null);
        }
    }

    /**
     * Sounds that belong together (one track on several speakers). They start in two steps: first every sound
     * opens its file; when all are open, the start position is read <b>once</b> for all of them, every sound
     * seeks there, and when all have seeked they are handed to the engine together. So the speakers play the
     * same moment of the song, even if one file opened much more slowly than another.
     */
    private static final class StartGroup {
        /** How long the first speaker waits for the others at the engine before the ones that are there start anyway. */
        private static final long PLAY_TIMEOUT_MS = 500;

        private final Barrier opened;
        private final Barrier seeked;
        private boolean startKnown;
        private double start;
        // the last step: the engine has started a member's OpenAL source; it is held at its first sample until all are there
        private int expected;      // members that will still reach the engine
        private final List<IpodSoundInstance> waiting = new java.util.ArrayList<>();
        private final List<Integer> sources = new java.util.ArrayList<>();
        private boolean started;

        StartGroup(int size) {
            this.opened = new Barrier(size);
            this.seeked = new Barrier(size);
            this.expected = size;
        }

        /**
         * The engine has just started this member (on the sound thread). Its source is put back to its very first
         * sample and held there; when the last member arrives, all of them start with one OpenAL call, so they play
         * the same sample at the same moment. A member that comes after the group started just plays.
         */
        synchronized void enginePlayed(IpodSoundInstance sound, int source) {
            if (started) return;
            AL10.alSourceRewind(source);
            waiting.add(sound);
            sources.add(source);
            if (waiting.size() == 1) {
                CompletableFuture.delayedExecutor(PLAY_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(this::timeout);
            }
            if (waiting.size() >= expected) startAll(false);
        }

        /** A member was stopped before the engine ever started it: nobody waits for it any more. */
        synchronized void memberGone() {
            expected--;
            if (!started && !waiting.isEmpty() && waiting.size() >= expected) startAll(false);
        }

        // a member never reached the engine (for example the engine had no free channel): start the ones that did
        private synchronized void timeout() {
            if (!started) startAll(true);
        }

        private void startAll(boolean late) {
            started = true;
            int[] live = new int[sources.size()];
            int n = 0;
            for (int i = 0; i < waiting.size(); i++) {
                if (!waiting.get(i).released) live[n++] = sources.get(i); // a stopped one may already be another sound's
            }
            if (n > 0) AL10.alSourcePlayv(java.util.Arrays.copyOf(live, n));
            LOGGER.info("{} speakers started together{}", n, late ? " (not all of them were ready)" : "");
        }

        /** The start position of the whole group: the first sound to ask reads it, the others get the same value. */
        synchronized double start(DoubleSupplier position) {
            if (!startKnown) {
                start = position.getAsDouble();
                startKnown = true;
            }
            return start;
        }
    }

    /** Makes the sounds in the list (those we decode ourselves) start together. Call before playing them. */
    public static void startTogether(List<? extends SoundInstance> sounds) {
        List<IpodSoundInstance> ours = sounds.stream()
                .filter(s -> s instanceof IpodSoundInstance i && i.opener != null)
                .map(s -> (IpodSoundInstance) s).toList();
        if (ours.size() < 2) return;
        StartGroup shared = new StartGroup(ours.size());
        for (IpodSoundInstance sound : ours) sound.group = shared;
    }

    /**
     * The sound was stopped by us. A stream that is still being opened is closed when it is ready; one the engine
     * never attached (it was stopped first) is closed after a moment. Closing twice does nothing.
     */
    public void release() {
        boolean first = !released; // release can be called more than once; the group must hear it only once
        released = true;
        StartGroup together = group;
        if (first && together != null && !enginePlayed) together.memberGone();
        CompletableFuture.runAsync(this::closeStream, CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS));
    }

    /**
     * Called by {@link SpeakerSync} on the sound thread right after the engine started this sound's OpenAL source.
     * A sound in a start group is held there until the whole group can start at once.
     */
    void onEnginePlay(int source) {
        enginePlayed = true;
        StartGroup together = group;
        if (together != null) together.enginePlayed(this, source);
    }

    private void closeStream() {
        PcmAudioStream s = stream;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    // A vanilla music disc sound
    public static IpodSoundInstance forDisc(SoundEvent sound, @Nullable Vec3 pos, float volume) {
        return new IpodSoundInstance(sound, null, "disc", true, pos, volume, ChannelMode.MIX, () -> 0, -1);
    }

    /**
     * A music disc played by {@code IpodPlayer} but decoded by us, so it can start in the middle
     * (for example when the iPod is taken out of a dock that was playing it).
     */
    public static IpodSoundInstance forDiscAt(Opener opener, String label, @Nullable Vec3 pos, float volume,
                                              double startSeconds) {
        return new IpodSoundInstance(borrowedEvent(), opener, label, true, pos, volume, ChannelMode.MIX, () -> startSeconds, -1);
    }

    /**
     * A sound a dock plays (not controlled by IpodPlayer): a disc decoded by us so it can start anywhere.
     * {@code startSeconds} is asked when the stream opens, so the time spent loading is not lost.
     * {@code channel} is the speaker's side (ignored without a position: the player's ears get stereo).
     */
    public static IpodSoundInstance forDock(Opener opener, String label, @Nullable Vec3 pos, float volume,
                                            ChannelMode channel, DoubleSupplier startSeconds) {
        return forDock(opener, label, pos, volume, channel, startSeconds, -1);
    }

    /** Like above, ending at {@code endSeconds} of the file (a chapter inside a longer file); -1 = the end of the file. */
    public static IpodSoundInstance forDock(Opener opener, String label, @Nullable Vec3 pos, float volume,
                                            ChannelMode channel, DoubleSupplier startSeconds, double endSeconds) {
        return new IpodSoundInstance(borrowedEvent(), opener, label, false, pos, volume, channel, startSeconds, endSeconds);
    }

    /** A file from the library, from a speaker ({@code pos}, playing {@code channel}) or in the player's ears (null). */
    public static IpodSoundInstance forFile(Path file, @Nullable Vec3 pos, float volume, ChannelMode channel,
                                            double startSeconds, double endSeconds) {
        return new IpodSoundInstance(borrowedEvent(), mode -> AudioDecoders.open(file, mode),
                file.toString(), true, pos, volume, channel, () -> startSeconds, endSeconds);
    }

    // borrow a vanilla streamed sound so the engine takes its streaming path;
    // getStream() below swaps in our own audio data
    private static SoundEvent borrowedEvent() {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("music_disc.cat"));
    }

    private IpodSoundInstance(SoundEvent sound, @Nullable Opener opener, String label, boolean managed,
                              @Nullable Vec3 pos, float volume, ChannelMode channel,
                              DoubleSupplier startSeconds, double endSeconds) {
        super(sound, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.opener = opener;
        this.label = label;
        this.managed = managed;
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.positional = pos != null;
        // a sound in the world must be mono to fade with distance; one in the player's ears keeps its stereo
        this.channel = positional ? (channel.isMono() ? channel : ChannelMode.MIX) : ChannelMode.STEREO;

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
            release();
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
        StartGroup together = group;
        if (opener == null) {
            // a normal disc: let the game load its own audio file as usual
            future = super.getStream(soundBuffers, sound, looping);
        } else if (together == null) {
            // a file or a disc that we decode ourselves, streaming: open, go to the start position, play
            future = CompletableFuture.supplyAsync(() -> prepare(openFile(), startSeconds.getAsDouble()),
                    Util.backgroundExecutor());
        } else {
            // one of several speakers: open, wait for the others, all go to the same position, wait, play together.
            // A sound whose file fails to open still arrives at both steps, so nobody waits for it.
            future = CompletableFuture.supplyAsync(this::openFile, Util.backgroundExecutor())
                    .whenComplete((opened, error) -> {
                        together.opened.arrive();
                        if (error != null) together.seeked.arrive();
                    })
                    .thenCompose(opened -> together.opened.await().thenApplyAsync(ignored -> {
                        try {
                            return prepare(opened, together.start(startSeconds));
                        } finally {
                            together.seeked.arrive();
                        }
                    }, Util.backgroundExecutor()))
                    .thenCompose(ready -> together.seeked.await().thenApply(ignored -> ready));
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

    // Opens the file with this sound's channels (runs on a background thread)
    private PcmAudioStream openFile() {
        try {
            return opener.open(channel);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    // Moves an opened stream to its start position and end, and makes it this sound's stream (background thread)
    private AudioStream prepare(PcmAudioStream opened, double start) {
        try {
            if (start > 0) {
                try {
                    opened.seekToFrame(Math.round(start * opened.sampleRate()));
                } catch (IOException e) {
                    // the position is unusable after a failed seek: start again from the beginning
                    LOGGER.warn("Could not seek to {} s in {}; playing from the start", start, label, e);
                    opened.close();
                    opened = opener.open(channel);
                }
            }
            if (endSeconds >= 0) opened.limitToFrame(Math.round(endSeconds * opened.sampleRate()));
            opened.setBassBoost(net.foxglove.icraft.client.IpodSettings.bassBoost().db()); // the listener's own setting
            opened.preload(PRELOAD_SECONDS); // so the engine does not decode on its own thread right before it starts
            if (!managed) {
                LOGGER.info("Dock sound {} opened: {} Hz, {} s in, {}", label, opened.sampleRate(), start,
                        positional ? "from a speaker (" + channel + ")" : "in the player's ears");
            }
            if (released) { // stopped while it was opening: nobody will play it
                opened.close();
                return new SilentAudioStream();
            }
            stream = opened;
            return opened;
        } catch (IOException e) {
            closeQuietly(opened);
            throw new CompletionException(e);
        }
    }

    private static void closeQuietly(PcmAudioStream s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}
