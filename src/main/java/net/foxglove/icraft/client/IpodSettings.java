package net.foxglove.icraft.client;

import net.foxglove.icraft.ClientConfig;
import net.foxglove.icraft.RepeatMode;

/** Shuffle and repeat. Global (not per iPod); read from the config once and saved when the screen closes. */
public class IpodSettings {
    private static boolean shuffle;
    private static RepeatMode repeat = RepeatMode.OFF;
    private static boolean loaded = false;
    private static boolean changed = false;

    private static void load() {
        if (loaded) return;
        shuffle = ClientConfig.SHUFFLE.get();
        repeat = ClientConfig.REPEAT.get();
        loaded = true;
    }

    public static boolean shuffle() {
        load();
        return shuffle;
    }

    public static RepeatMode repeat() {
        load();
        return repeat;
    }

    /** Flips shuffle and rebuilds the play order of the current list, so it takes effect at once. */
    public static void toggleShuffle() {
        load();
        shuffle = !shuffle;
        changed = true;
        save(); // saved at once, not only when the screen closes
        IpodPlayer.shuffleChanged();
    }

    public static void cycleRepeat() {
        load();
        repeat = repeat.next();
        changed = true;
        save();
        DockControl.modesChanged();
    }

    /** Writes the settings to the config file (at every change, and when the iPod screen closes). */
    public static void save() {
        if (!changed) return;
        ClientConfig.SHUFFLE.set(shuffle);
        ClientConfig.REPEAT.set(repeat);
        ClientConfig.SPEC.save();
        changed = false;
    }
}
