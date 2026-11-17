package net.foxglove.icraft.block;

import net.foxglove.icraft.RepeatMode;

import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;

/**
 * What a docking station is playing: the single source of truth for every player near it. The server owns it
 * and changes it; clients get a copy and work out where the song is from the game time.
 *
 * It is plain Java (no Minecraft types) so it can be tested on its own. Times are in game ticks (20 per second).
 * Discs are identified by their index in the docked iPod's disc list; their lengths come from a function.
 */
public final class DockPlayback {
    /** A guard for dock catch-up after a very long gap (for example the chunk was unloaded for days). */
    private static final int MAX_ADVANCES = 10_000;
    private static final IntPredicate ALL = i -> true;

    private int[] order;          // disc indices in play order
    private int index;            // position in 'order'
    private boolean shuffle;
    private RepeatMode repeat;
    private long positionBase;    // ticks into the current disc at the last change
    private long changedAt;       // game time of the last change
    private boolean paused;
    private boolean stopped;
    private int sequence;         // grows with every change, so a client can tell that something happened

    private final Random random;

    private DockPlayback(Random random) {
        this.random = random;
    }

    // ---- creating ----

    /** Starts the list at disc {@code start}. */
    public static DockPlayback start(int discCount, int start, boolean shuffle, RepeatMode repeat, long now, Random random,
                                     long startTicks) {
        DockPlayback p = new DockPlayback(random);
        p.shuffle = shuffle;
        p.repeat = repeat;
        p.order = p.buildOrder(discCount, start, shuffle);
        p.index = shuffle ? 0 : start;
        p.positionBase = Math.max(0, startTicks);
        p.changedAt = now;
        p.sequence = 1;
        return p;
    }

    /** A plain copy of the state, for saving and sending. */
    public record Snapshot(int[] order, int index, boolean shuffle, int repeat, long positionBase, long changedAt,
                           boolean paused, boolean stopped, int sequence) {}

    public Snapshot snapshot() {
        return new Snapshot(order.clone(), index, shuffle, repeat.ordinal(), positionBase, changedAt, paused, stopped, sequence);
    }

    public static DockPlayback restore(Snapshot s, Random random) {
        DockPlayback p = new DockPlayback(random);
        p.order = s.order().clone();
        p.index = Math.max(0, Math.min(s.index(), Math.max(0, p.order.length - 1)));
        p.shuffle = s.shuffle();
        RepeatMode[] modes = RepeatMode.values();
        p.repeat = modes[Math.max(0, Math.min(s.repeat(), modes.length - 1))];
        p.positionBase = s.positionBase();
        p.changedAt = s.changedAt();
        p.paused = s.paused();
        p.stopped = s.stopped() || p.order.length == 0;
        p.sequence = s.sequence();
        return p;
    }

    // ---- reading ----

    public boolean isPlaying() {
        return !stopped;
    }

    public boolean isPaused() {
        return paused;
    }

    public int sequence() {
        return sequence;
    }

    public boolean shuffle() {
        return shuffle;
    }

    public RepeatMode repeat() {
        return repeat;
    }

    /** The disc that is playing (index in the iPod's disc list). */
    public int currentDisc() {
        return order[index];
    }

    /** The item that comes after the current one in the play order (wraps around), or -1 if there is none. */
    public int peekNext() {
        return order.length == 0 ? -1 : order[(index + 1) % order.length];
    }

    /** Ticks into the current disc. */
    public long positionTicks(long now) {
        return paused || stopped ? positionBase : Math.max(0, positionBase + (now - changedAt));
    }

    // ---- changing (all return true when something changed) ----

    public void play(int disc, int discCount, boolean shuffle, RepeatMode repeat, long now) {
        play(disc, discCount, shuffle, repeat, now, 0);
    }

    /** Like {@link #play(int, int, boolean, RepeatMode, long)}, starting {@code startOffsetTicks} into the first item (a resume). */
    public void play(int disc, int discCount, boolean shuffle, RepeatMode repeat, long now, long startOffsetTicks) {
        this.shuffle = shuffle;
        this.repeat = repeat;
        order = buildOrder(discCount, disc, shuffle);
        index = shuffle ? 0 : disc;
        restartAt(Math.max(0, startOffsetTicks), now);
        stopped = false;
        paused = false;
        sequence++;
    }

    public void pause(long now) {
        if (stopped || paused) return;
        positionBase = positionTicks(now);
        paused = true;
        sequence++;
    }

    public void resume(long now) {
        if (stopped || !paused) return;
        changedAt = now;
        paused = false;
        sequence++;
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        sequence++;
    }

    /** Next disc (wraps around, like the Next button). Also starts a stopped or paused dock. */
    public void next(long now) {
        next(now, ALL);
    }

    /** Like {@link #next(long)}, skipping items that cannot be played (for example a file not uploaded yet). */
    public void next(long now, IntPredicate playable) {
        for (int i = 0; i < order.length; i++) {
            index = (index + 1) % order.length;
            if (playable.test(currentDisc())) break;
        }
        restartAt(0, now);
        stopped = false;
        paused = false;
        sequence++;
    }

    public void previous(long now) {
        previous(now, ALL);
    }

    public void previous(long now, IntPredicate playable) {
        for (int i = 0; i < order.length; i++) {
            index = Math.floorMod(index - 1, order.length);
            if (playable.test(currentDisc())) break;
        }
        restartAt(0, now);
        stopped = false;
        paused = false;
        sequence++;
    }

    /** Skips within the disc by {@code delta} ticks. Past the end counts as the disc ending. Ignored while paused or stopped. */
    public void seekBy(long delta, long now, IntToLongFunction lengthOf) {
        seekBy(delta, now, lengthOf, ALL);
    }

    public void seekBy(long delta, long now, IntToLongFunction lengthOf, IntPredicate playable) {
        if (stopped || paused) return;
        long target = Math.max(0, positionTicks(now) + delta);
        long length = lengthOf.applyAsLong(currentDisc());
        if (length > 0 && target >= length) {
            advanceOnEnd(now, playable);
        } else {
            restartAt(target, now);
        }
        sequence++;
    }

    /** Changes shuffle and repeat. The current disc stays current; what follows changes. */
    public void setModes(boolean shuffle, RepeatMode repeat, int discCount) {
        boolean changed = shuffle != this.shuffle || repeat != this.repeat;
        if (shuffle != this.shuffle) {
            int current = currentDisc();
            this.shuffle = shuffle;
            order = buildOrder(discCount, current, shuffle);
            index = shuffle ? 0 : current;
        }
        this.repeat = repeat;
        if (changed) sequence++;
    }

    /**
     * Moves on to the next disc when the current one has run out. Called every tick. Returns true when
     * something changed. Loops, so a long gap is caught up in one go.
     */
    public boolean tick(long now, IntToLongFunction lengthOf) {
        return tick(now, lengthOf, ALL);
    }

    /** Like {@link #tick(long, IntToLongFunction)}; an item that is not playable is skipped when its turn comes. */
    public boolean tick(long now, IntToLongFunction lengthOf, IntPredicate playable) {
        if (stopped || paused) return false;
        boolean changed = false;
        for (int steps = 0; steps < MAX_ADVANCES; steps++) {
            long length = Math.max(1, lengthOf.applyAsLong(currentDisc()));
            long position = positionBase + (now - changedAt);
            if (position < length) return changed;

            long endTime = changedAt + (length - positionBase); // the moment the disc ended
            changed = true;
            if (!advanceOnEnd(endTime, playable)) {
                sequence++;
                return true;
            }
        }
        restartAt(0, now); // an absurdly long gap: just start the current disc again
        sequence++;
        return true;
    }

    // ---- inside ----

    private void restartAt(long ticks, long now) {
        positionBase = ticks;
        changedAt = now;
    }

    /** The current disc ended at game time {@code at}. Returns false when playback stopped. */
    private boolean advanceOnEnd(long at, IntPredicate playable) {
        for (int tries = 0; tries <= order.length; tries++) {
            if (!advanceOnce(at, playable.test(currentDisc()))) return false;
            if (playable.test(currentDisc())) return true;
        }
        stopped = true; // nothing in the list can be played
        return false;
    }

    private boolean advanceOnce(long at, boolean currentPlayable) {
        if (repeat == RepeatMode.ONE && currentPlayable) {
            restartAt(0, at);
            sequence++;
            return true;
        }
        int next = index + 1;
        if (next >= order.length) {
            if (repeat == RepeatMode.OFF) {
                stopped = true;
                return false;
            }
            if (shuffle) order = reshuffled(order.length, currentDisc());
            next = 0;
        }
        index = next;
        restartAt(0, at);
        sequence++;
        return true;
    }

    private int[] buildOrder(int count, int first, boolean shuffle) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = i;
        if (!shuffle || count < 2) return result;
        result[first] = 0;
        result[0] = first;
        for (int i = count - 1; i > 1; i--) { // Fisher-Yates over everything after the first
            int j = 1 + random.nextInt(i);
            int tmp = result[i];
            result[i] = result[j];
            result[j] = tmp;
        }
        return result;
    }

    // A fresh random order for the next round that does not start with the disc that just played
    private int[] reshuffled(int count, int notFirst) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = i;
        for (int i = count - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = result[i];
            result[i] = result[j];
            result[j] = tmp;
        }
        if (count > 1 && result[0] == notFirst) {
            int j = 1 + random.nextInt(count - 1);
            result[0] = result[j];
            result[j] = notFirst;
        }
        return result;
    }
}
