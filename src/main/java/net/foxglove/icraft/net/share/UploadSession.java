package net.foxglove.icraft.net.share;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/** One file a player is uploading right now: where it goes, how far it is, when the last data came in. */
public final class UploadSession {
    public final UUID player;
    public final String hash;
    public final String ext;
    public final long size;
    public final ChunkAssembler assembler;
    public long lastDataMillis = System.currentTimeMillis();

    public UploadSession(UUID player, String hash, String ext, long size, ChunkAssembler assembler) {
        this.player = player;
        this.hash = hash;
        this.ext = ext;
        this.size = size;
        this.assembler = assembler;
    }

    /** Stops the upload and deletes what arrived so far. */
    public void abort() {
        try {
            assembler.close();
            Files.deleteIfExists(assembler.partFile());
        } catch (IOException ignored) {
        }
    }
}
