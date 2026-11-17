package net.foxglove.icraft.net.share;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Decides which files of the server's shared cache to delete. Plain Java, no Minecraft. */
public final class CachePolicy {
    public record Entry(String hash, long size, long lastAccessMillis) {}

    private CachePolicy() {}

    /**
     * Files to delete: first everything older than {@code maxAgeMillis}, then the least recently used ones until
     * the cache plus {@code extraBytes} (a file about to be added) fits into {@code maxBytes}. Files in
     * {@code keep} (they belong to a queue that is playing) are never chosen.
     *
     * @return the hashes to delete, or {@code null} if {@code extraBytes} cannot be made to fit
     */
    public static List<String> toDelete(List<Entry> entries, long now, long maxAgeMillis, long maxBytes,
                                        long extraBytes, Set<String> keep) {
        List<String> delete = new ArrayList<>();
        long total = 0;
        List<Entry> remaining = new ArrayList<>();
        for (Entry e : entries) {
            if (!keep.contains(e.hash()) && now - e.lastAccessMillis() > maxAgeMillis) {
                delete.add(e.hash());
            } else {
                total += e.size();
                remaining.add(e);
            }
        }
        remaining.sort(Comparator.comparingLong(Entry::lastAccessMillis)); // oldest use first
        for (Entry e : remaining) {
            if (total + extraBytes <= maxBytes) break;
            if (keep.contains(e.hash())) continue;
            delete.add(e.hash());
            total -= e.size();
        }
        return total + extraBytes <= maxBytes ? delete : null;
    }
}
