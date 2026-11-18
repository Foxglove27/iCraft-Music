package net.foxglove.icraft.net.share;

import java.util.Optional;
import java.util.Set;

/**
 * One item of a dock's shared file queue: a whole file, or a range inside a longer file (a chapter of an .m4b).
 * Times are game ticks. Entries come from a player's game, so {@link #validated} clamps everything.
 */
public record QueueEntry(String hash, String ext, String title, String album, int startTicks, int endTicks,
                         int lengthTicks) {
    public static final Set<String> EXTENSIONS = Set.of("ogg", "mp3", "flac", "wav", "m4a", "m4b");
    public static final int MIN_LENGTH_TICKS = 20;           // 1 second
    public static final int MAX_LENGTH_TICKS = 12 * 3600 * 20; // 12 hours
    public static final int MAX_TEXT = 48;

    /**
     * True if the hash and extension can be used to build a file name: 64 lower-case hex digits and a known audio
     * extension. Clients check this too, because a queue entry can come from a server that is not trusted.
     */
    public static boolean isSafe(String hash, String ext) {
        return hash != null && ext != null && hash.matches("[0-9a-f]{64}") && EXTENSIONS.contains(ext);
    }

    /** The entry with sane values, or empty if it cannot be trusted (bad hash or extension). */
    public static Optional<QueueEntry> validated(String hash, String ext, String title, String album,
                                                 int startTicks, int endTicks, int lengthTicks) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) return Optional.empty();
        if (ext == null || !EXTENSIONS.contains(ext)) return Optional.empty();
        int length = Math.max(MIN_LENGTH_TICKS, Math.min(lengthTicks, MAX_LENGTH_TICKS));
        int start = Math.max(0, Math.min(startTicks, MAX_LENGTH_TICKS));
        int end = endTicks < 0 ? -1 : Math.max(start + MIN_LENGTH_TICKS, Math.min(endTicks, MAX_LENGTH_TICKS));
        if (end == -1 && start >= length) start = 0;
        return Optional.of(new QueueEntry(hash, ext, clip(title), clip(album), start, end, length));
    }

    private static String clip(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\p{Cntrl}", "");
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    /** How long this item plays, in ticks: the chapter range, or the rest of the file. */
    public int playTicks() {
        return endTicks >= 0 ? endTicks - startTicks : Math.max(MIN_LENGTH_TICKS, lengthTicks - startTicks);
    }
}
