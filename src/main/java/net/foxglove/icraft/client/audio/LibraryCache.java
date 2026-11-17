package net.foxglove.icraft.client.audio;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The scanned library, kept in memory. Scanning (slow on a network share) runs on one background thread and
 * publishes an immutable {@link Snapshot}; the screen reads the latest snapshot and never touches the disk.
 */
public final class LibraryCache {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** One finished scan. {@code generation} grows with every scan; {@code scanning} is true while the next one runs. */
    public record Snapshot(List<LocalMusic.Album> albums, List<LocalMusic.Album> nas, List<LocalMusic.Book> books, List<LocalMusic.Book> podcasts,
                           List<LocalMusic.Book> nasBooks, List<LocalMusic.Book> nasPodcasts, long generation, boolean scanning) {
        /** The same content marked as scanning or not. */
        Snapshot withScanning(boolean value) {
            return new Snapshot(albums, nas, books, podcasts, nasBooks, nasPodcasts, generation, value);
        }

        /** True if the lists show the same albums, books and shows with the same number of entries. */
        public boolean sameContentAs(Snapshot other) {
            return albumSignature(albums).equals(albumSignature(other.albums))
                    && albumSignature(nas).equals(albumSignature(other.nas))
                    && bookSignature(books).equals(bookSignature(other.books))
                    && bookSignature(podcasts).equals(bookSignature(other.podcasts))
                    && bookSignature(nasBooks).equals(bookSignature(other.nasBooks))
                    && bookSignature(nasPodcasts).equals(bookSignature(other.nasPodcasts));
        }

        private static String albumSignature(List<LocalMusic.Album> list) {
            return signature(list.stream().map(a -> a.name().getString() + "|" + a.files().size()).toList());
        }

        private static String bookSignature(List<LocalMusic.Book> list) {
            return signature(list.stream().map(b -> b.key() + "|" + b.chapters().size()).toList());
        }

        private static String signature(List<String> parts) {
            return String.join("\n", parts);
        }
    }

    private static volatile Snapshot current = new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, false);
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean AGAIN = new AtomicBoolean();
    /** A scan that took at least this long (a network share) is not repeated within {@link #STALE_MS}. */
    private static final long SLOW_MS = 1000;
    private static final long STALE_MS = 30_000;
    private static volatile long lastFinishedAt = 0;
    private static volatile long lastDurationMs = 0;

    private LibraryCache() {}

    /** The latest scan (never null, empty before the first scan has finished). */
    public static Snapshot get() {
        return current;
    }

    /**
     * For opening the iPod: scans again unless a scan is running, or the last one was slow (a big share) and
     * finished less than 30 seconds ago. A small local library is cheap, so it is scanned on every open.
     */
    public static void refreshIfStale() {
        if (RUNNING.get()) return;
        if (lastDurationMs >= SLOW_MS && System.currentTimeMillis() - lastFinishedAt < STALE_MS) return;
        refreshAsync();
    }

    /** Starts a scan in the background; if one is running already, it runs once more when it is done. */
    public static void refreshAsync() {
        if (!RUNNING.compareAndSet(false, true)) {
            AGAIN.set(true);
            return;
        }
        current = current.withScanning(true);
        Thread thread = new Thread(LibraryCache::scanLoop, "iCraft library scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static void scanLoop() {
        try {
            do {
                AGAIN.set(false);
                long start = System.currentTimeMillis();
                try {
                    List<LocalMusic.Album> albums = LocalMusic.albums();
                    List<LocalMusic.Album> nas = LocalMusic.nasAlbums();
                    List<LocalMusic.Book> books = LocalMusic.books();
                    List<LocalMusic.Book> podcasts = LocalMusic.podcasts();
                    List<LocalMusic.Book> nasBooks = LocalMusic.nasBooks();
                    List<LocalMusic.Book> nasPodcasts = LocalMusic.nasPodcasts();
                    current = new Snapshot(albums, nas, books, podcasts, nasBooks, nasPodcasts, current.generation() + 1, true);
                    lastDurationMs = System.currentTimeMillis() - start;
                    lastFinishedAt = System.currentTimeMillis();
                    LOGGER.info("Library scanned in {} ms: {} albums, {} NAS albums, {} audiobooks, {} podcasts, {} NAS audiobooks, {} NAS podcasts",
                            System.currentTimeMillis() - start, albums.size(), nas.size(), books.size(), podcasts.size(),
                            nasBooks.size(), nasPodcasts.size());
                } catch (RuntimeException | Error e) {
                    LOGGER.warn("The library scan failed", e);
                }
            } while (AGAIN.get());
        } finally {
            current = current.withScanning(false);
            RUNNING.set(false);
        }
    }
}
