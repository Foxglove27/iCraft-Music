package net.foxglove.icraft.client;

import net.foxglove.icraft.BassBoost;
import net.foxglove.icraft.ClientConfig;
import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.WorldSounds;

/** Shuffle, repeat, bass boost and world sounds. Global (not per iPod); read from the config once and saved when the screen closes. */
public class IpodSettings {
    private static boolean shuffle;
    private static RepeatMode repeat = RepeatMode.OFF;
    private static BassBoost bassBoost = BassBoost.OFF;
    private static WorldSounds worldSounds = WorldSounds.QUIETER;
    private static boolean loaded = false;
    private static boolean changed = false;

    private static void load() {
        if (loaded) return;
        shuffle = ClientConfig.SHUFFLE.get();
        repeat = ClientConfig.REPEAT.get();
        bassBoost = ClientConfig.BASS_BOOST.get();
        worldSounds = ClientConfig.WORLD_SOUNDS.get();
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

    /** The bass boost of what this player hears. Read when a sound opens its file (can be a background thread). */
    public static BassBoost bassBoost() {
        try {
            load();
        } catch (IllegalStateException e) {
            return BassBoost.OFF; // the config is not loaded yet
        }
        return bassBoost;
    }

    /** Next bass level; what is playing restarts at the same second so the change is heard at once. */
    public static void cycleBassBoost() {
        load();
        bassBoost = bassBoost.next();
        changed = true;
        save();
        IpodPlayer.audioSettingsChanged();
        DockClientPlayer.restartAll();
    }

    /** How loud the world stays while music plays on the player (read every tick by WorldSoundFader). */
    public static WorldSounds worldSounds() {
        try {
            load();
        } catch (IllegalStateException e) {
            return WorldSounds.NORMAL; // the config is not loaded yet
        }
        return worldSounds;
    }

    /** Next world sounds level; the fader moves to it within a second. */
    public static void cycleWorldSounds() {
        load();
        worldSounds = worldSounds.next();
        changed = true;
        save();
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
        ClientConfig.BASS_BOOST.set(bassBoost);
        ClientConfig.WORLD_SOUNDS.set(worldSounds);
        ClientConfig.SPEC.save();
        changed = false;
    }
}
