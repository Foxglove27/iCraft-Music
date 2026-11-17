package net.foxglove.icraft.net.share;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which shared files the docks on this server are playing (server thread only). The shared file cache keeps
 * these files, and only players near such a dock may download them: the server is not a general file server.
 */
public final class DockQueues {
    /** A listener must be this close to a dock (blocks) to download the files of its queue. */
    public static final double DOWNLOAD_DISTANCE = 160; // a 2x4 wall reaches 64 blocks, doubled is 128, and speakers sit up to 32 blocks from the dock

    private static final Map<GlobalPos, List<String>> QUEUES = new HashMap<>();

    private DockQueues() {}

    public static void set(GlobalPos dock, List<QueueEntry> entries) {
        if (entries.isEmpty()) {
            QUEUES.remove(dock);
        } else {
            QUEUES.put(dock, entries.stream().map(QueueEntry::hash).distinct().toList());
        }
    }

    public static void remove(GlobalPos dock) {
        QUEUES.remove(dock);
    }

    public static void clear() {
        QUEUES.clear();
    }

    /** Every file of every dock queue (these must not be deleted from the cache). */
    public static Set<String> allHashes() {
        Set<String> all = new HashSet<>();
        for (List<String> hashes : QUEUES.values()) all.addAll(hashes);
        return all;
    }

    /** True if the player is near a dock whose queue contains the file. */
    public static boolean mayDownload(ServerPlayer player, String hash) {
        for (Map.Entry<GlobalPos, List<String>> e : QUEUES.entrySet()) {
            if (!e.getKey().dimension().equals(player.level().dimension()) || !e.getValue().contains(hash)) continue;
            if (player.distanceToSqr(e.getKey().pos().getX() + 0.5, e.getKey().pos().getY() + 0.5, e.getKey().pos().getZ() + 0.5)
                    <= DOWNLOAD_DISTANCE * DOWNLOAD_DISTANCE) {
                return true;
            }
        }
        return false;
    }
}
