package net.foxglove.icraft;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration, registered as {@code ModConfig.Type.CLIENT} in {@link iCraft}.
 * Written to {@code config/icraft-client.toml}; loaded on physical clients only,
 * never on dedicated servers.
 */
public class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Note: the .comment(...) below is NOT a code comment — its text is written verbatim
    // into the generated .toml file above the "volume" entry.
    public static final ModConfigSpec.DoubleValue VOLUME = BUILDER
            .comment("iPod volume, from 0.0 (silent) to 1.0 (full)")
            .defineInRange("volume", 1.0, 0.0, 1.0);

    // The full spec, handed to {@code ModContainer.registerConfig(...)} in the mod constructor.
    public static final ModConfigSpec SPEC = BUILDER.build();
}
