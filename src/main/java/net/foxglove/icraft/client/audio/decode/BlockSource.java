package net.foxglove.icraft.client.audio.decode;

import java.io.IOException;

/**
 * Base for decoders that produce audio one block (frame) at a time. It hands out exactly the
 * number of frames the caller asks for and keeps the rest of the block for the next call.
 */
abstract class BlockSource implements PcmSource {
    private short[] block = new short[0];
    private int blockFrames;
    private int blockPos;

    /** Decodes the next block and passes it to {@link #setBlock}. Returns false at the end. */
    protected abstract boolean fillBlock() throws IOException;

    /** The array is read straight away, so the decoder may reuse it for the next block. */
    protected final void setBlock(short[] data, int frames) {
        block = data;
        blockFrames = frames;
        blockPos = 0;
    }

    protected final void discardBlock() {
        blockFrames = 0;
        blockPos = 0;
    }

    protected final void skipInBlock(int frames) {
        blockPos = Math.min(blockFrames, blockPos + Math.max(0, frames));
    }

    protected final int blockFrames() {
        return blockFrames;
    }

    @Override
    public int read(short[] out, int maxFrames) throws IOException {
        int ch = channels();
        int done = 0;
        while (done < maxFrames) {
            if (blockPos >= blockFrames) {
                if (!fillBlock()) break;
                continue; // a block may be empty; ask again
            }
            int n = Math.min(maxFrames - done, blockFrames - blockPos);
            System.arraycopy(block, blockPos * ch, out, done * ch, n * ch);
            blockPos += n;
            done += n;
        }
        return done;
    }
}
