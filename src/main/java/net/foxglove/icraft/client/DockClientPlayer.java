package net.foxglove.icraft.client;

import com.mojang.logging.LogUtils;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.client.audio.AudioDecoders;
import net.foxglove.icraft.client.audio.DiscAudio;
import net.foxglove.icraft.client.share.SharedFiles;
import net.foxglove.icraft.net.share.QueueEntry;
import java.nio.file.Path;
import net.foxglove.icraft.client.audio.IpodSoundInstance;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
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
        int lastSequence = -1; // sequence of the dock's playback at the last tick
        DockBlockEntity dock;   // the block entity this state belongs to
        boolean owned;          // the player owns this dock
        boolean candidate;      // the play/pause key would act on this dock (owned and in range, or audible)
        boolean locallyPaused; // this player paused the dock for themselves
        int pausedSequence = -1;
        boolean audible;       // playing and in earshot (before a local pause); double distance to the nearest speaker below
        double distSq = Double.MAX_VALUE;
        String lastReason = ""; // what the dock last told the log, so it is logged only when it changes
        long checkAt = Long.MAX_VALUE; // when to log whether the started sounds are really playing

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
        boolean playingList = dock.hasIpod() && pb != null && pb.isPlaying();
        s.audible = discPlaying && heard;
        s.candidate = s.owned
                ? playingList && (ownerEars || inEarshot(player, s.outputs, STOP_MARGIN))
                : s.audible;
        s.lastSequence = pb != null ? pb.sequence() : -1;
        s.distSq = nearestDistSq(player, s.outputs);
        // a local pause ends when the player really leaves earshot (with the wide margin, so the edge cannot flip it)
        // or the dock starts something new or stops
        boolean stillNear = ownerEars || inEarshot(player, s.outputs, STOP_MARGIN);
        if (s.locallyPaused && (!stillNear || !playingList)) { // track changes and the owner's pause do not bring the sound back
            s.locallyPaused = false;
        }
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
        if (s.locallyPaused) {
            log(s, pos, "paused by you");
            stopSounds(s);
            return;
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
        if (now >= s.checkAt && s.isPlaying()) { // two seconds later: did the sound engine really start them?
            s.checkAt = Long.MAX_VALUE;
            StringBuilder active = new StringBuilder();
            for (SoundInstance sound : s.sounds) active.append(mc.getSoundManager().isActive(sound) ? "active " : "NOT ACTIVE ");
            LOGGER.info("Dock at {}: two seconds after the start the sounds are: {}", pos.toShortString(), active.toString().trim());
        }
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

    /**
     * Pauses (or resumes) the music of the nearest dock that is playing for this player, for this player only.
     * Returns false if no dock is playing in earshot. Resuming joins the song at the dock's live position.
     */
    /**
     * The play/pause key for a dock the player owns: the real pause or resume (everybody hears it).
     * Returns false if the player owns no playing dock in range (the caller then handles their own iPod).
     */
    public static boolean pressPlayPause() {
        State best = null;
        for (State s : ALL) {
            if (s.candidate && s.owned && (best == null || s.distSq < best.distSq)) best = s;
        }
        if (best == null || best.dock == null) return false;
        DockControl.togglePauseAt(best.dock);
        return true;
    }

    /**
     * The listener's only control: stops the music of the nearest dock they hear, for themselves. Nothing else
     * changes. It ends when they walk out of earshot or the dock starts a new list.
     */
    public static void stopForMe() {
        stopForMe(null);
    }

    /** Same, for the dock at {@code only} (the one whose iPod screen is open), or the nearest one if null. Returns true if something was stopped. */
    public static boolean stopForMe(@Nullable BlockPos only) {
        State best = null;
        for (State s : ALL) {
            if (!s.audible || s.owned || s.dock == null) continue;
            if (only != null && !s.dock.getBlockPos().equals(only)) continue;
            if (best == null || s.distSq < best.distSq) best = s;
        }
        Minecraft mc = Minecraft.getInstance();
        if (best == null) return false;
        best.locallyPaused = true;
        best.pausedSequence = best.lastSequence;
        stopSounds(best);
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.icraft.dock.stopped_for_you"), true);
        return true;
    }

    private static int pausedSequenceOf(State s) {
        return s.lastSequence;
    }

    private static boolean inEarshot(Player player, List<DockOutputs.Output> outputs, double margin) {
        for (DockOutputs.Output o : outputs) {
            double range = o.range() + margin;
            if (player.position().distanceToSqr(o.pos()) <= range * range) return true;
        }
        return false;
    }

    private static void startSounds(Level level, DockBlockEntity dock, State s, DockPlayback pb) {
        stopSounds(s);
        s.sequence = pb.sequence();
        s.playingOutputs = s.outputs;

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
            IpodSoundInstance sound = IpodSoundInstance.forDock(downmix -> DiscAudio.open(path, downmix),
                    path.toString(), null, 1.0F, start);
            s.sounds.add(sound);
            mc.getSoundManager().play(sound);
            return;
        }
        for (DockOutputs.Output o : s.outputs) {
            IpodSoundInstance sound = IpodSoundInstance.forDock(downmix -> DiscAudio.open(path, downmix),
                    path.toString(), o.pos(), o.volume(), start);
            s.sounds.add(sound);
            mc.getSoundManager().play(sound);
        }
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
            IpodSoundInstance sound = IpodSoundInstance.forDock(d -> AudioDecoders.open(file, d), label, null, 1.0F, start, end);
            s.sounds.add(sound);
            mc.getSoundManager().play(sound);
            return;
        }
        for (DockOutputs.Output o : s.outputs) {
            IpodSoundInstance sound = IpodSoundInstance.forDock(d -> AudioDecoders.open(file, d), label, o.pos(), o.volume(), start, end);
            s.sounds.add(sound);
            mc.getSoundManager().play(sound);
        }
    }

    private static void stopSounds(State s) {
        if (s.sounds.isEmpty()) return;
        for (SoundInstance sound : s.sounds) Minecraft.getInstance().getSoundManager().stop(sound);
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

    /** Stops every dock sound (leaving the world). */
    public static void stopAll() {
        for (State s : new ArrayList<>(ALL)) stopSounds(s);
        ALL.clear();
    }
}
