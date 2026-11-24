package net.foxglove.icraft.client;

import net.foxglove.icraft.BassBoost;
import net.foxglove.icraft.ClientConfig;
import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.WorldSounds;

/** The iPod's Settings: shuffle, repeat, bass boost, world sounds and three on/off switches (stereo speakers, game music, shared downloads). Global (not per iPod); read from the config once and saved when the screen closes. */
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

    // ---- on/off switches that other code reads straight from the config (DockOutputs, ClientEvents, SharedFiles) ----

    public static boolean stereoSpeakers() {
        return flag(ClientConfig.STEREO_SPEAKERS);
    }

    public static boolean silenceBackgroundMusic() {
        return flag(ClientConfig.SILENCE_BACKGROUND_MUSIC);
    }

    public static boolean downloadSharedFiles() {
        return flag(ClientConfig.DOWNLOAD_SHARED_FILES);
    }

    /** The speakers switch between stereo and the mix within half a second (the outputs check sees new channels). */
    public static void toggleStereoSpeakers() {
        flip(ClientConfig.STEREO_SPEAKERS);
    }

    public static void toggleSilenceBackgroundMusic() {
        flip(ClientConfig.SILENCE_BACKGROUND_MUSIC);
    }

    /** Off stops a running download at once (SharedFiles checks it every tick). */
    public static void toggleDownloadSharedFiles() {
        flip(ClientConfig.DOWNLOAD_SHARED_FILES);
    }

    /** The label above a playing dock (B14); read by DockRenderer every frame. */
    public static boolean dockDisplay() {
        return flag(ClientConfig.DOCK_DISPLAY);
    }

    public static void toggleDockDisplay() {
        flip(ClientConfig.DOCK_DISPLAY);
    }

    /** Headphones on players who listen (B18); read by HeadphonesLayer every frame. */
    public static boolean showHeadphones() {
        return flag(ClientConfig.SHOW_HEADPHONES);
    }

    public static void toggleShowHeadphones() {
        flip(ClientConfig.SHOW_HEADPHONES);
    }

    /** Your own picture behind the iPod's display (B22). */
    public static boolean wallpaper() {
        return flag(ClientConfig.WALLPAPER);
    }

    public static void toggleWallpaper() {
        flip(ClientConfig.WALLPAPER);
        if (wallpaper()) net.foxglove.icraft.client.audio.Wallpaper.refresh(); // read the picture now, not only on the next open
    }

    private static boolean flag(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return value.getDefault(); // the config is not loaded yet
        }
    }

    private static void flip(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        value.set(!value.get());
        ClientConfig.SPEC.save();
    }

    /**
     * "Reset to defaults" (after the confirmation page): every setting of the Settings page goes back to its default.
     * Not the volume, the library folders or the mutes. Only what really changed is restarted or re-sent.
     */
    public static void resetToDefaults() {
        load();
        boolean shuffleWas = shuffle;
        RepeatMode repeatWas = repeat;
        BassBoost bassWas = bassBoost;
        shuffle = ClientConfig.SHUFFLE.getDefault();
        repeat = ClientConfig.REPEAT.getDefault();
        bassBoost = ClientConfig.BASS_BOOST.getDefault();
        worldSounds = ClientConfig.WORLD_SOUNDS.getDefault();
        ClientConfig.STEREO_SPEAKERS.set(ClientConfig.STEREO_SPEAKERS.getDefault());
        ClientConfig.SILENCE_BACKGROUND_MUSIC.set(ClientConfig.SILENCE_BACKGROUND_MUSIC.getDefault());
        ClientConfig.DOWNLOAD_SHARED_FILES.set(ClientConfig.DOWNLOAD_SHARED_FILES.getDefault());
        ClientConfig.DOCK_DISPLAY.set(ClientConfig.DOCK_DISPLAY.getDefault());
        ClientConfig.SHOW_HEADPHONES.set(ClientConfig.SHOW_HEADPHONES.getDefault());
        ClientConfig.WALLPAPER.set(ClientConfig.WALLPAPER.getDefault());
        changed = true;
        save(); // also writes the four switches above
        if (shuffle != shuffleWas) IpodPlayer.shuffleChanged();
        if (repeat != repeatWas) DockControl.modesChanged();
        if (bassBoost != bassWas) {
            IpodPlayer.audioSettingsChanged();
            DockClientPlayer.restartAll();
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.icraft_music.settings_reset"), true);
        }
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
