package net.foxglove.icraft;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER =new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue VOLUME = BUILDER
            .comment("iPod volume, from 0.0 (silent) to 1.0 (full)")
            .defineInRange("volume", 1.0, 0.0, 1.0);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
