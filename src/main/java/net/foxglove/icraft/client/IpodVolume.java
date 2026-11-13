package net.foxglove.icraft.client;

import net.minecraft.util.Mth;

public class IpodVolume {
    private static float volume = 1.0F; // 0.0 =silent, 1.0 = full

    public static float get() {
        return volume;
    }

    public static void set (float value) {
        volume =Mth.clamp(value, 0.0F, 1.0F);
    }
}
