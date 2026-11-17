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

    public static final ModConfigSpec.BooleanValue SHUFFLE = BUILDER
            .comment("Play the tracks of a list in random order")
            .define("shuffle", false);

    public static final ModConfigSpec.EnumValue<RepeatMode> REPEAT = BUILDER
            .comment("What happens when a track ends: OFF (stop after the last), LIST (start over) or ONE (same track again)")
            .defineEnum("repeat", RepeatMode.OFF);

    public static final ModConfigSpec.BooleanValue DOWNLOAD_SHARED_FILES = BUILDER
            .comment("Download the music files that other players share at a docking station on a server (private",
                    "servers only), so you can hear them. Switch off to only see the music notes.")
            .define("downloadSharedFiles", true);

    // The full spec, handed to {@code ModContainer.registerConfig(...)} in the mod constructor.
    public static final ModConfigSpec SPEC = BUILDER.build();
}
