package net.foxglove.icraft.net.share;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Puts the chunks of an uploaded or downloaded file back together, checks that they arrive in order and that
 * the finished file has the promised size and SHA-256. Nothing here knows about Minecraft.
 */
public final class ChunkAssembler implements AutoCloseable {
    public enum Result { OK, DONE, BAD_OFFSET, TOO_LARGE }

    private final Path partFile;
    private final long size;
    private final String expectedHash;
    private final OutputStream out;
    private final MessageDigest digest;
    private long written;
    private boolean closed;

    /** @param expectedHash lower-case hex SHA-256 of the whole file */
    public ChunkAssembler(Path partFile, long size, String expectedHash) throws IOException {
        this.partFile = partFile;
        this.size = size;
        this.expectedHash = expectedHash.toLowerCase();
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Files.createDirectories(partFile.toAbsolutePath().getParent());
        this.out = Files.newOutputStream(partFile);
    }

    public long written() {
        return written;
    }

    /** Takes the chunk that starts at {@code offset}. Chunks must come in order and without gaps. */
    public Result accept(long offset, byte[] data) throws IOException {
        if (closed || offset != written) return Result.BAD_OFFSET;
        if (data.length == 0 || written + data.length > size) return Result.TOO_LARGE;
        out.write(data);
        digest.update(data);
        written += data.length;
        return written == size ? Result.DONE : Result.OK;
    }

    /** True if all bytes arrived and the content matches the promised hash. Closes the file. */
    public boolean verify() throws IOException {
        close();
        return written == size && HexFormat.of().formatHex(digest.digest()).equals(expectedHash);
    }

    public Path partFile() {
        return partFile;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    /** The SHA-256 of a byte array as lower-case hex (for tests and small things). */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
