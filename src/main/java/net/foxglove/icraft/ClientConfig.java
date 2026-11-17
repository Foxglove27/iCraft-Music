package net.foxglove.icraft;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration, registered as {@code ModConfig.Type.CLIENT} in {@link iCraft}.
 * Written to {@code config/icraft_music-client.toml}; loaded on physical clients only,
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

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_MUSIC_FOLDERS = BUILDER
            .comment("Extra folders to read music from, in addition to <game folder>/ipod/music (for example a mounted",
                    "network share). Absolute paths, or relative to the game folder. A share must be mounted first",
                    "(smb:// addresses do not work; use the mount path, e.g. /mnt/nas-music or //nas/music on Windows). Albums are folders that directly",
                    "contain audio files; Artist/Album folders are shown as 'Artist - Album'. The mod never writes there.")
            .defineListAllowEmpty("extraMusicFolders", java.util.List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_AUDIOBOOK_FOLDERS = BUILDER
            .comment("Extra folders to read audiobooks from, in addition to <game folder>/ipod/audiobooks.",
                    "One folder per book (also Author/Book).")
            .defineListAllowEmpty("extraAudiobookFolders", java.util.List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_PODCAST_FOLDERS = BUILDER
            .comment("Extra folders to read podcasts from, in addition to <game folder>/ipod/podcasts.",
                    "One folder per show.")
            .defineListAllowEmpty("extraPodcastFolders", java.util.List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue SILENCE_BACKGROUND_MUSIC = BUILDER
            .comment("Stop the game's own background music while an iPod or a dock plays music for you")
            .define("silenceBackgroundMusic", true);

    // The full spec, handed to {@code ModContainer.registerConfig(...)} in the mod constructor.
    public static final ModConfigSpec SPEC = BUILDER.build();
}
