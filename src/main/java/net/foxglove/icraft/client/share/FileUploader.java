package net.foxglove.icraft.client.share;

import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.net.FileChunkPayload;
import net.foxglove.icraft.net.FileOfferPayload;
import net.foxglove.icraft.net.FileStatusPayload;
import net.foxglove.icraft.net.share.QueueEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The owner's side of file sharing: keeps the file that plays now and the next two uploaded to the server.
 * One file at a time: offer it, wait for the server's go-ahead, then send it in pieces (two per tick) that are
 * read from disk as they go, never the whole file in memory.
 */
public final class FileUploader {
    /** A file the owner wants to share. */
    public record Item(String hash, Path file, String ext, long size, String title) {}

    private static final int LOOKAHEAD = 3;          // the current file and the next two
    private static final int CHUNKS_PER_TICK = 2;
    private static final long OFFER_TIMEOUT_MS = 15_000;

    private static final Map<String, Item> ITEMS = new HashMap<>();
    private static final Set<String> DONE = new HashSet<>();     // the server has these
    private static final Set<String> REFUSED = new HashSet<>();  // the server said no

    private static String offered;           // waiting for the server's answer
    private static long offeredAt;
    private static Item sending;             // being sent right now
    private static FileChannel channel;
    private static long sent;
    private static long lastMessage;
    private static boolean noticeShown;

    private FileUploader() {}

    /** A new list was started: these are the files in it. */
    public static void reset(List<Item> items) {
        closeChannel();
        ITEMS.clear();
        DONE.clear();
        REFUSED.clear();
        offered = null;
        sending = null;
        noticeShown = false;
        for (Item i : items) ITEMS.put(i.hash(), i);
    }

    private static void closeChannel() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
    }

    private static void message(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(text, true);
    }

    /** The server answered about one of our files. Returns true if it was one of ours. */
    public static boolean onStatus(FileStatusPayload status) {
        Item item = ITEMS.get(status.hash());
        if (item == null) return false;
        switch (status.status()) {
            case FileStatusPayload.HAVE -> {
                DONE.add(item.hash());
                if (item.hash().equals(offered)) offered = null;
                if (sending != null && sending.hash().equals(item.hash())) {
                    closeChannel();
                    sending = null;
                }
            }
            case FileStatusPayload.SEND -> {
                if (!item.hash().equals(offered)) return true;
                offered = null;
                try {
                    channel = FileChannel.open(item.file(), StandardOpenOption.READ);
                    sending = item;
                    sent = 0;
                    if (!noticeShown) {
                        noticeShown = true;
                        message(Component.translatable("message.icraft_music.share.notice"));
                    }
                } catch (IOException e) {
                    REFUSED.add(item.hash());
                }
            }
            default -> { // REFUSED (and anything else): do not try this file again for this list
                REFUSED.add(item.hash());
                if (item.hash().equals(offered)) offered = null;
                if (sending != null && sending.hash().equals(item.hash())) {
                    closeChannel();
                    sending = null;
                }
                message(Component.translatable("message.icraft_music.share.refused." + status.reason(), item.title()));
            }
        }
        return true;
    }

    /** Every client tick, while the owner plays a list of shared files at {@code dock}. */
    public static void tick(DockBlockEntity dock) {
        if (!ServerConfig.sharingEnabled() || ITEMS.isEmpty()) return;

        if (sending != null) {
            try {
                for (int i = 0; i < CHUNKS_PER_TICK && sending != null; i++) {
                    ByteBuffer buf = ByteBuffer.allocate((int) Math.min(FileChunkPayload.MAX_CHUNK, sending.size() - sent));
                    int n = channel.read(buf, sent);
                    if (n <= 0) throw new IOException("The file ended early");
                    PacketDistributor.sendToServer(new FileChunkPayload(sending.hash(), sent, java.util.Arrays.copyOf(buf.array(), n)));
                    sent += n;
                    if (sent >= sending.size()) { // all sent: the server verifies it and answers HAVE
                        closeChannel();
                        sending = null;
                    }
                }
            } catch (IOException e) {
                REFUSED.add(sending.hash());
                closeChannel();
                sending = null;
            }
            long now = System.currentTimeMillis();
            if (sending != null && now - lastMessage > 1000) {
                lastMessage = now;
                message(Component.translatable("message.icraft_music.share.sharing", sending.title(), (int) (100 * sent / sending.size())));
            }
            return;
        }

        if (offered != null) {
            if (System.currentTimeMillis() - offeredAt > OFFER_TIMEOUT_MS) offered = null; // no answer: ask again
            return;
        }

        // the next file that the server does not have yet: the current one, then the next two in play order
        DockPlayback pb = dock.getPlayback();
        if (pb == null || !pb.isPlaying()) return;
        List<QueueEntry> queue = dock.getQueue();
        DockPlayback.Snapshot snap = pb.snapshot();
        for (int k = 0; k < Math.min(LOOKAHEAD, snap.order().length); k++) {
            int index = snap.order()[(snap.index() + k) % snap.order().length];
            if (index < 0 || index >= queue.size()) continue;
            Item item = ITEMS.get(queue.get(index).hash());
            if (item == null || DONE.contains(item.hash()) || REFUSED.contains(item.hash())) continue;
            offered = item.hash();
            offeredAt = System.currentTimeMillis();
            PacketDistributor.sendToServer(new FileOfferPayload(item.hash(), item.size(), item.ext()));
            return;
        }
    }
}
