package net.foxglove.icraft.client;

import net.foxglove.icraft.ClientConfig;
import net.minecraft.util.Mth;

public class IpodVolume {
    private static float volume = 1.0F; // 0.0 = silent, 1.0 = full
    private static boolean loaded = false;
    private static boolean changed = false;

    public static float get() {
        if (!loaded) {
            volume = ClientConfig.VOLUME.get().floatValue(); // read the saved value, once
            loaded = true;
        }
        return volume;
    }

    public static void set(float value) {
        volume = Mth.clamp(value, 0.0F, 1.0F);
        loaded = true;
        changed = true;
    }

    // Writes the current volume to the config file
    public static void save() {
        if (!changed) return;
        ClientConfig.VOLUME.set((double) volume);
        ClientConfig.VOLUME.save();
        changed = false;
    }
}