package net.foxglove.icraft;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side settings (<code>serverconfig/icraft-server.toml</code> in the world folder). They are sent to the
 * players on joining, so the game knows whether file sharing is on.
 *
 * File sharing copies a player's own music, audiobooks and podcasts to the server and from there to the players
 * near their docking station. It is for PRIVATE use (your own server, the people you live with) and is OFF by
 * default. Whoever runs the server is responsible for the licences of what is shared.
 */
public class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ALLOW_FILE_SHARING = BUILDER
            .comment("Let players share their own music files at a docking station: the files are uploaded to this",
                    "server and sent to the players near the dock. For private use only (your own server, the people",
                    "you live with). You are responsible for the licences of what is shared.")
            .define("allowFileSharing", false);

    public static final ModConfigSpec.BooleanValue PRIVATE_NETWORK_ONLY = BUILDER
            .comment("Only share files with players who connect from a private network address (192.168.x.x, 10.x.x.x,",
                    "172.16-31.x.x, localhost, IPv6 fc00::/7 and fe80::/10). Players from the internet are refused.",
                    "Switching this off is your own responsibility.")
            .define("privateNetworkOnly", true);

    public static final ModConfigSpec.IntValue MAX_FILE_SIZE_MB = BUILDER
            .comment("Largest single file that may be uploaded, in MiB (audiobooks can be big)")
            .defineInRange("maxFileSizeMb", 512, 1, 4096);

    public static final ModConfigSpec.IntValue MAX_CACHE_SIZE_MB = BUILDER
            .comment("Total size of the shared file cache on the server, in MiB. The least recently used files are deleted first.")
            .defineInRange("maxCacheSizeMb", 4096, 16, 1_000_000);

    public static final ModConfigSpec.IntValue PLAYER_QUOTA_MB = BUILDER
            .comment("How much one player may have in the cache at the same time, in MiB")
            .defineInRange("perPlayerQuotaMb", 1024, 1, 1_000_000);

    public static final ModConfigSpec.IntValue CACHE_DAYS = BUILDER
            .comment("Shared files that were not used for this many days are deleted")
            .defineInRange("cacheDays", 14, 1, 3650);

    public static final ModConfigSpec.IntValue MAX_QUEUE_ITEMS = BUILDER
            .comment("How many tracks a dock queue may hold (a list is sent in one message, so at most 100)")
            .defineInRange("maxQueueItems", 100, 1, 100);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {}

    /** True if file sharing is switched on (on a client: as the server told it on joining). */
    public static boolean sharingEnabled() {
        try {
            return SPEC.isLoaded() && ALLOW_FILE_SHARING.get();
        } catch (IllegalStateException e) {
            return false; // the config is not available (yet)
        }
    }
}
