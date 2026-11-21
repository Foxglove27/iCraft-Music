package net.foxglove.icraft.client;

import com.mojang.logging.LogUtils;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.client.audio.AudioDecoders;
import net.foxglove.icraft.client.audio.ChannelMode;
import net.foxglove.icraft.client.audio.DiscAudio;
import net.foxglove.icraft.client.share.SharedFiles;
import net.foxglove.icraft.net.share.QueueEntry;
import java.nio.file.Path;
import net.foxglove.icraft.client.audio.IpodSoundInstance;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Makes a dock heard for everybody near it. The server keeps what the dock plays ({@link DockPlayback});
 * every client with the dock loaded follows it: when the dock is playing a disc and the player is within
 * range of one of its speakers, local sounds play from the speakers at the position the server says
 * (so somebody who walks up mid-song hears the song where it is). It also shows the music notes.
 */
public final class DockClientPlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int OUTPUT_CHECK_TICKS = 5;
    private static final int NOTE_TICKS = 8;
    /** A player has to come this close (in blocks, from the speaker's range) before the sound starts... */
    private static final double START_MARGIN = 0;
    /** ...and has to go this much further away before it stops again, so the edge does not flicker. */
    private static final double STOP_MARGIN = 8;

    /** What this client keeps for one dock. */
    private static final class State {
        List<DockOutputs.Output> outputs = List.of();
        long lastOutputCheck = -1000; // long ago (not Long.MIN_VALUE: 'now - MIN_VALUE' overflows and is never >= 5)
        int noteTicks;
        // what is playing here
        int sequence = -1;
        List<DockOutputs.Output> playingOutputs = List.of();
        final List<SoundInstance> sounds = new ArrayList<>();
        boolean failedLogged;
        DockBlockEntity dock;   // the block entity this state belongs to
        boolean owned;          // the player owns this dock
        boolean audible;        // playing and in earshot (before a stop by the player)
        double distSq = Double.MAX_VALUE; // squared distance to the nearest speaker
        String lastReason = ""; // what the dock last told the log, so it is logged only when it changes
        long checkAt = Long.MAX_VALUE; // when to log whether the started sounds are really playing
        long startedAt;        // game time of the last start of the sounds
        long nextRevive;       // earliest game time to restart sounds the engine dropped
        int revives;           // restarts since the sounds were last all alive
        boolean listening;     // someone else's dock plays (or is paused) and this player is in earshot: the HUD shows it
        long seenAt = Long.MIN_VALUE / 2; // game time of the last tick (a dock in an unloaded chunk stops ticking)

        boolean isPlaying() {
            return !sounds.isEmpty();
        }
    }

    private static final List<State> ALL = new ArrayList<>(); // so everything can be stopped on logout

    private DockClientPlayer() {}

    private static State state(DockBlockEntity dock) {
        if (dock.clientData instanceof State existing) return existing;
        State created = new State();
        dock.clientData = created;
        ALL.add(created);
        return created;
    }

    /** The client ticker of a dock block entity. */
    public static void clientTick(Level level, BlockPos pos, BlockState blockState, DockBlockEntity dock) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        State s = state(dock);

        long now = level.getGameTime();
        if (now - s.lastOutputCheck >= OUTPUT_CHECK_TICKS) {
            s.lastOutputCheck = now;
            s.outputs = DockOutputs.of(level, dock, s.outputs);
        }

        // music notes for everybody who sees the dock make music (a disc, or the owner's own file)
        if (dock.hasIpod() && dock.isMakingMusic() && ++s.noteTicks >= NOTE_TICKS) {
            s.noteTicks = 0;
            for (DockOutputs.Output o : s.outputs) {
                double x = o.top().x + (level.random.nextDouble() - 0.5) * (o.multiblock() ? 1.2 : 0.5);
                double z = o.top().z + (level.random.nextDouble() - 0.5) * (o.multiblock() ? 1.2 : 0.5);
                level.addParticle(ParticleTypes.NOTE, x, o.top().y + 0.1, z, level.random.nextInt(25) / 24.0, 0, 0);
            }
        }

        // sound: only for a disc the server plays, and only while a speaker is in earshot
        DockPlayback pb = dock.getPlayback();
        // a dock without any speaker plays a disc in its owner's own ears, like an iPod they hold
        boolean ownerEars = s.outputs.isEmpty() && dock.isOwner(player)
                && player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= DockBlockEntity.CONTROL_DISTANCE * DockBlockEntity.CONTROL_DISTANCE;
        boolean discPlaying = dock.hasIpod() && pb != null && pb.isPlaying() && !pb.isPaused()
                && (!s.outputs.isEmpty() || ownerEars);
        boolean heard = ownerEars || inEarshot(player, s.outputs, s.isPlaying() ? STOP_MARGIN : START_MARGIN);
        s.dock = dock;
        s.owned = dock.hasIpod() && dock.isOwner(player);
        s.audible = discPlaying && heard;
        s.distSq = nearestDistSq(player, s.outputs);
        s.seenAt = now;
        s.listening = dock.hasIpod() && !dock.isOwner(player) && pb != null && pb.isPlaying() && !s.outputs.isEmpty()
                && inEarshot(player, s.outputs, s.listening ? STOP_MARGIN : START_MARGIN);
        // shared files are fetched from twice the speaker range on, so they are usually here before the player is in earshot
        boolean nearForDownload = dock.isFilesMode() && dock.hasIpod() && pb != null && pb.isPlaying() && !pb.isPaused()
                && !s.outputs.isEmpty() && inEarshotFactor(player, s.outputs, 2.0);
        if (nearForDownload && !s.owned) { // the owner's files are on their own computer: they never download them
            List<QueueEntry> queue = dock.getQueue();
            List<QueueEntry> wanted = new ArrayList<>();
            int cur = pb.currentDisc(), next = pb.peekNext();
            if (cur >= 0 && cur < queue.size()) wanted.add(queue.get(cur));
            if (next >= 0 && next < queue.size() && next != cur) wanted.add(queue.get(next));
            SharedFiles.want(pos.asLong(), wanted);
        } else if (dock.isFilesMode()) {
            SharedFiles.want(pos.asLong(), List.of());
        }
        if (dock.isFilesMode() && discPlaying && heard) {
            // shared files: make sure this computer has the one that plays now and the next one
            List<QueueEntry> queue = dock.getQueue();
            int cur = pb.currentDisc();
            if (cur >= 0 && cur < queue.size() && SharedFiles.pathFor(queue.get(cur)) == null) {
                if (s.owned) {
                    log(s, pos, "your own file \"" + queue.get(cur).title() + "\" is not on this computer (a list from an earlier session): start the list again");
                    stopSounds(s);
                    return;
                }
                log(s, pos, "waiting for the shared file \"" + queue.get(cur).title() + "\" to arrive");
                stopSounds(s);
                return;
            }
        }
        if (!discPlaying || !heard) {
            log(s, pos, !dock.hasIpod() ? "the dock holds no iPod"
                    : pb == null ? "the dock plays no disc" + (dock.isExternalPlaying() ? " (the owner plays one of their own files; only they hear it)" : "")
                    : !pb.isPlaying() ? "the dock's disc list stopped"
                    : pb.isPaused() ? "paused"
                    : s.outputs.isEmpty() ? "the dock has no usable speaker (" + dock.getSpeakers().size() + " saved)"
                    : "out of earshot of all " + s.outputs.size() + " speaker(s)");
            stopSounds(s);
            return;
        }

        if (s.sequence != pb.sequence() || !s.playingOutputs.equals(s.outputs)) {
            log(s, pos, "starting disc " + pb.currentDisc() + " (sequence " + pb.sequence() + ") at "
                    + pb.positionTicks(level.getGameTime()) / 20 + " s on " + s.outputs.size() + " usable speaker(s) of "
                    + dock.getSpeakers().size() + " saved" + (s.outputs.isEmpty() ? ", in the owner's ears" : ""));
            startSounds(level, dock, s, pb);
            s.checkAt = now + 40;
        }
        reviveDeadSounds(level, dock, s, pb, pos, now);
        if (now >= s.checkAt && s.isPlaying()) { // two seconds later: did the sound engine really start them?
            s.checkAt = Long.MAX_VALUE;
            StringBuilder active = new StringBuilder();
            for (SoundInstance sound : s.sounds) active.append(mc.getSoundManager().isActive(sound) ? "active " : "NOT ACTIVE ");
            LOGGER.info("Dock at {}: two seconds after the start the sounds are: {}", pos.toShortString(), active.toString().trim());
        }
    }

    /**
     * The sound engine can drop a sound without telling us (a volume set to 0 and back, an audio device switch,
     * walking away and back). If a sound of this dock is no longer active although its file has not ended, start
     * again at the position the server says. Retries get further apart (1 s, 2 s, ... 5 s) so a file that
     * cannot play does not loop.
     */
    private static void reviveDeadSounds(Level level, DockBlockEntity dock, State s, DockPlayback pb, BlockPos pos, long now) {
        if (!s.isPlaying() || now - s.startedAt < 40) return;
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        boolean dead = false;
        for (SoundInstance sound : s.sounds) {
            if (sounds.isActive(sound)) continue;
            if (sound instanceof IpodSoundInstance ipod && (ipod.streamEnded() || ipod.streamFailed())) continue; // really over
            dead = true;
        }
        if (!dead) {
            s.revives = 0;
            return;
        }
        if (now < s.nextRevive) return;
        s.nextRevive = now + 20L * (1 + Math.min(s.revives, 4));
        s.revives++;
        LOGGER.info("Dock at {}: a sound stopped before the disc ended, restarting at {} s (try {})",
                pos.toShortString(), pb.positionTicks(now) / 20, s.revives);
        int tries = s.revives;
        startSounds(level, dock, s, pb);
        s.revives = tries; // startSounds does not reset it; it is reset when everything is alive again
        s.startedAt = now;
    }

    // One line in the log whenever the reason why this dock is silent (or not) changes
    private static void log(State s, BlockPos pos, String reason) {
        if (reason.equals(s.lastReason)) return;
        s.lastReason = reason;
        LOGGER.info("Dock at {}: {}", pos.toShortString(), reason);
    }

    private static boolean inEarshotFactor(Player player, List<DockOutputs.Output> outputs, double factor) {
        for (DockOutputs.Output o : outputs) {
            double range = o.range() * factor;
            if (player.position().distanceToSqr(o.pos()) <= range * range) return true;
        }
        return false;
    }

    private static double nearestDistSq(Player player, List<DockOutputs.Output> outputs) {
        double best = Double.MAX_VALUE;
        for (DockOutputs.Output o : outputs) best = Math.min(best, player.position().distanceToSqr(o.pos()));
        return best;
    }

    /** Whether a dock plays for this player right now (for the mute). */
    public static boolean anyAudible() {
        for (State st : ALL) if (st.audible) return true;
        return false;
    }

    private static boolean inEarshot(Player player, List<DockOutputs.Output> outputs, double margin) {
        for (DockOutputs.Output o : outputs) {
            double range = o.range() + margin;
            if (player.position().distanceToSqr(o.pos()) <= range * range) return true;
        }
        return false;
    }

    /** True while the player hears at least one dock (its sounds are playing). */
    /** A dock without speakers plays in this player's ears (they own it) and is not muted. */
    public static boolean playsOnYou() {
        for (State s : ALL) {
            if (!s.isPlaying() || !s.outputs.isEmpty() || !s.owned || s.dock == null) continue;
            if (!IpodMute.silences(s.dock.getIpod().get(ModDataComponents.IPOD_ID.get()))) return true;
        }
        return false;
    }

    /** What another player's dock in earshot plays, for the now-playing panel of a listener. */
    public record Heard(Component title, Component subtitle, ItemStack disc, boolean paused, boolean muted,
                        boolean waiting, String owner) {}

    /** The nearest dock of another player that this player is in earshot of and that plays (or is paused), or null. */
    @Nullable
    public static Heard heard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        long now = mc.level.getGameTime();
        State best = null;
        for (State s : ALL) {
            if (!s.listening || s.dock == null || s.dock.isRemoved() || now - s.seenAt > 5) continue;
            if (best == null || s.distSq < best.distSq) best = s;
        }
        if (best == null) return null;
        DockBlockEntity dock = best.dock;
        DockPlayback pb = dock.getPlayback();
        if (pb == null) return null;
        int index = pb.currentDisc();
        boolean muted = IpodMute.silences(dock.getIpod().get(ModDataComponents.IPOD_ID.get()));
        if (dock.isFilesMode()) {
            List<QueueEntry> queue = dock.getQueue();
            if (index < 0 || index >= queue.size()) return null;
            QueueEntry entry = queue.get(index);
            return new Heard(Component.literal(entry.title()), Component.literal(entry.album()), ItemStack.EMPTY,
                    pb.isPaused(), muted, SharedFiles.pathFor(entry) == null, dock.getOwnerName());
        }
        List<ItemStack> discs = IpodItem.getDiscs(dock.getIpod());
        ItemStack disc = index >= 0 && index < discs.size() ? discs.get(index) : ItemStack.EMPTY;
        return new Heard(DockControl.discName(disc), Component.translatable("screen.icraft_music.ipod.discs"), disc,
                pb.isPaused(), muted, false, dock.getOwnerName());
    }

    public static boolean hasActiveSounds() {
        for (State s : ALL) {
            if (s.isPlaying()) return true;
        }
        return false;
    }

    private static void startSounds(Level level, DockBlockEntity dock, State s, DockPlayback pb) {
        stopSounds(s);
        Minecraft.getInstance().getMusicManager().stopPlaying(); // the game's own music makes way for the dock's
        s.sequence = pb.sequence();
        s.playingOutputs = s.outputs;
        s.startedAt = level.getGameTime();

        if (dock.isFilesMode()) {
            startFileSounds(dock, s, pb);
            return;
        }
        List<ItemStack> discs = IpodItem.getDiscs(dock.getIpod());
        int index = pb.currentDisc();
        if (index < 0 || index >= discs.size()) {
            LOGGER.warn("Dock: disc {} is not in the docked iPod ({} discs)", index, discs.size());
            return;
        }
        Optional<net.minecraft.core.Holder<JukeboxSong>> song = JukeboxSong.fromStack(level.registryAccess(), discs.get(index));
        if (song.isEmpty()) {
            LOGGER.warn("Dock: {} is not a music disc the game knows", discs.get(index));
            return;
        }
        ResourceLocation path = DiscAudio.resolve(song.get().value().soundEvent().value());
        if (path == null) {
            if (!s.failedLogged) {
                s.failedLogged = true;
                LOGGER.warn("Could not find the audio file of {}", song.get().value().description().getString());
            }
            return;
        }

        // asked when the stream opens, so the time spent loading is not lost
        java.util.function.DoubleSupplier start = () -> pb.positionTicks(level.getGameTime()) / 20.0;
        Minecraft mc = Minecraft.getInstance();
        if (s.outputs.isEmpty()) { // no speaker: straight into the owner's ears
            IpodSoundInstance sound = IpodSoundInstance.forDock(mode -> DiscAudio.open(path, mode),
                    path.toString(), null, 1.0F, ChannelMode.STEREO, start);
            sound.setIpod(dock.getIpod().get(ModDataComponents.IPOD_ID.get()));
            s.sounds.add(sound);
            mc.getSoundManager().play(sound);
            return;
        }
        List<IpodSoundInstance> speakers = new ArrayList<>();
        for (DockOutputs.Output o : s.outputs) {
            IpodSoundInstance sound = IpodSoundInstance.forDock(mode -> DiscAudio.open(path, mode),
                    path.toString(), o.pos(), o.volume(), o.channel(), start);
            sound.setIpod(dock.getIpod().get(ModDataComponents.IPOD_ID.get()));
            s.sounds.add(sound);
            speakers.add(sound);
        }
        // all speakers that can be heard start at the same moment (a far one that the engine skips is not waited for)
        net.minecraft.world.phys.Vec3 listener = mc.player != null ? mc.player.position() : net.minecraft.world.phys.Vec3.ZERO;
        List<IpodSoundInstance> heard = new ArrayList<>();
        for (int i = 0; i < speakers.size(); i++) {
            if (DockOutputs.inRange(s.outputs.get(i), listener)) heard.add(speakers.get(i));
        }
        IpodSoundInstance.startTogether(heard);
        for (IpodSoundInstance sound : speakers) mc.getSoundManager().play(sound);
    }

    // The dock plays shared files: the owner's own originals, or the copy this computer downloaded
    private static void startFileSounds(DockBlockEntity dock, State s, DockPlayback pb) {
        List<QueueEntry> queue = dock.getQueue();
        int index = pb.currentDisc();
        if (index < 0 || index >= queue.size()) return;
        QueueEntry entry = queue.get(index);
        Path file = SharedFiles.pathFor(entry);
        if (file == null) {
            s.sequence = -1; // not here yet: try again on the next tick
            return;
        }
        Level level = dock.getLevel();
        // asked when the stream opens, so the time spent loading is not lost
        java.util.function.DoubleSupplier start = () -> entry.startTicks() / 20.0 + pb.positionTicks(level.getGameTime()) / 20.0;
        double end = entry.endTicks() >= 0 ? entry.endTicks() / 20.0 : -1;
        Minecraft mc = Minecraft.getInstance();
        String label = entry.title();
        if (s.outputs.isEmpty()) {
            IpodSoundInstance sound = IpodSoundInstance.forDock(mode -> AudioDecoders.open(file, mode), label, null, 1.0F,
                    ChannelMode.STEREO, start, end);
            sound.setIpod(dock.getIpod().get(ModDataComponents.IPOD_ID.get()));
            s.sounds.add(sound);
            mc.getSoundManager().play(sound);
            return;
        }
        List<IpodSoundInstance> speakers = new ArrayList<>();
        for (DockOutputs.Output o : s.outputs) {
            IpodSoundInstance sound = IpodSoundInstance.forDock(mode -> AudioDecoders.open(file, mode), label, o.pos(), o.volume(),
                    o.channel(), start, end);
            sound.setIpod(dock.getIpod().get(ModDataComponents.IPOD_ID.get()));
            s.sounds.add(sound);
            speakers.add(sound);
        }
        // the speakers in earshot start at the same moment (as for discs)
        net.minecraft.world.phys.Vec3 listener = mc.player != null ? mc.player.position() : net.minecraft.world.phys.Vec3.ZERO;
        List<IpodSoundInstance> heard = new ArrayList<>();
        for (int i = 0; i < speakers.size(); i++) {
            if (DockOutputs.inRange(s.outputs.get(i), listener)) heard.add(speakers.get(i));
        }
        IpodSoundInstance.startTogether(heard);
        for (IpodSoundInstance sound : speakers) mc.getSoundManager().play(sound);
    }

    private static void stopSounds(State s) {
        if (s.sounds.isEmpty()) return;
        for (SoundInstance sound : s.sounds) {
            Minecraft.getInstance().getSoundManager().stop(sound);
            if (sound instanceof IpodSoundInstance ours) ours.release();
        }
        s.sounds.clear();
        s.sequence = -1;
    }

    /** The dock block entity is gone (chunk unloaded, block broken): stop whatever it was playing here. */
    public static void cleanup(DockBlockEntity dock) {
        if (dock.clientData instanceof State s) {
            stopSounds(s);
            ALL.remove(s);
            dock.clientData = null;
        }
    }

    /** Starts the sounds of every dock that plays here again at the current position (an audio setting changed). */
    public static void restartAll() {
        for (State s : ALL) {
            if (s.isPlaying()) s.sequence = -1; // the next tick sees a "new" sequence and starts the sounds again
        }
    }

    /** Stops every dock sound (leaving the world). */
    public static void stopAll() {
        for (State s : new ArrayList<>(ALL)) stopSounds(s);
        ALL.clear();
    }
}
