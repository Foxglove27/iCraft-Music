package net.foxglove.icraft.net.share;

import com.mojang.logging.LogUtils;
import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.iCraft;
import net.foxglove.icraft.net.FileChunkPayload;
import net.foxglove.icraft.net.FileDataPayload;
import net.foxglove.icraft.net.FileOfferPayload;
import net.foxglove.icraft.net.FileRequestPayload;
import net.foxglove.icraft.net.FileStatusPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server's side of file sharing: takes uploads from players, sends files to the players near a dock, and
 * keeps the cache tidy. Everything is checked here again, nothing the client says is trusted.
 */
@EventBusSubscriber(modid = iCraft.MOD_ID)
public final class ShareServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNKS_PER_TICK = 2;        // 2 x 32 KiB per player per tick, so a big file cannot flood the connection
    private static final long UPLOAD_TIMEOUT_MS = 60_000;
    private static final int CLEANUP_TICKS = 6000;       // every 5 minutes

    private static final Map<UUID, UploadSession> UPLOADS = new HashMap<>();

    private static final class Download {
        final String hash;
        final long total;
        final java.nio.file.Path file;
        long offset;

        Download(String hash, long total, java.nio.file.Path file, long offset) {
            this.hash = hash;
            this.total = total;
            this.file = file;
            this.offset = offset;
        }
    }

    private static final Map<UUID, Download> DOWNLOADS = new HashMap<>();
    private static int cleanupTimer;

    private ShareServer() {}

    // ---- is sharing allowed for this player at all? ----

    /** A reason (translation key suffix) why this player may not share, or null if they may. */
    public static String refusal(ServerPlayer player) {
        if (!ServerConfig.ALLOW_FILE_SHARING.get()) return "off";
        if (ServerConfig.PRIVATE_NETWORK_ONLY.get() && !PrivateAddress.isPrivate(player.connection.getRemoteAddress())) {
            return "not_private";
        }
        return null;
    }

    private static void reply(ServerPlayer player, String hash, int status, String reason) {
        PacketDistributor.sendToPlayer(player, new FileStatusPayload(hash, status, reason));
    }

    // ---- uploads ----

    public static void onOffer(ServerPlayer player, FileOfferPayload offer) {
        SharedFileCache cache = SharedFileCache.get();
        String why = cache == null ? "off" : refusal(player);
        if (why != null) {
            reply(player, offer.hash(), FileStatusPayload.REFUSED, why);
            return;
        }
        String hash = offer.hash();
        if (!hash.matches("[0-9a-f]{64}") || !QueueEntry.EXTENSIONS.contains(offer.ext())) {
            reply(player, hash, FileStatusPayload.REFUSED, "bad_file");
            return;
        }
        long maxBytes = (long) ServerConfig.MAX_FILE_SIZE_MB.get() * 1024 * 1024;
        if (offer.size() <= 0 || offer.size() > maxBytes) {
            reply(player, hash, FileStatusPayload.REFUSED, "too_large");
            return;
        }
        if (cache.info(hash) != null) {
            cache.touch(hash);
            reply(player, hash, FileStatusPayload.HAVE, "");
            return;
        }
        if (cache.usedBy(player.getUUID()) + offer.size() > (long) ServerConfig.PLAYER_QUOTA_MB.get() * 1024 * 1024) {
            reply(player, hash, FileStatusPayload.REFUSED, "quota");
            return;
        }
        if (!cache.makeRoom(offer.size(), ageMillis(), (long) ServerConfig.MAX_CACHE_SIZE_MB.get() * 1024 * 1024,
                DockQueues.allHashes())) {
            reply(player, hash, FileStatusPayload.REFUSED, "cache_full");
            return;
        }

        UploadSession old = UPLOADS.remove(player.getUUID());
        if (old != null) old.abort();
        try {
            ChunkAssembler assembler = new ChunkAssembler(cache.partFile(hash), offer.size(), hash);
            UPLOADS.put(player.getUUID(), new UploadSession(player.getUUID(), hash, offer.ext(), offer.size(), assembler));
            LOGGER.info("File sharing: {} uploads {} ({} bytes, .{})", player.getGameProfile().getName(),
                    hash.substring(0, 12), offer.size(), offer.ext());
            reply(player, hash, FileStatusPayload.SEND, "");
        } catch (IOException e) {
            LOGGER.warn("Could not start the upload of {}", hash, e);
            reply(player, hash, FileStatusPayload.REFUSED, "error");
        }
    }

    public static void onChunk(ServerPlayer player, FileChunkPayload chunk) {
        UploadSession session = UPLOADS.get(player.getUUID());
        SharedFileCache cache = SharedFileCache.get();
        if (session == null || cache == null || !session.hash.equals(chunk.hash()) || refusal(player) != null) return;
        try {
            session.lastDataMillis = System.currentTimeMillis();
            ChunkAssembler.Result result = session.assembler.accept(chunk.offset(), chunk.data());
            if (result == ChunkAssembler.Result.OK) return;
            UPLOADS.remove(player.getUUID());
            if (result != ChunkAssembler.Result.DONE) {
                session.abort();
                reply(player, session.hash, FileStatusPayload.REFUSED, "bad_data");
                return;
            }
            if (session.assembler.verify()) {
                cache.add(session.assembler.partFile(), session.hash, session.ext, session.size, session.player);
                LOGGER.info("File sharing: {} finished and verified ({} bytes)", session.hash.substring(0, 12), session.size);
                reply(player, session.hash, FileStatusPayload.HAVE, "");
            } else {
                session.abort();
                LOGGER.warn("File sharing: the upload {} from {} did not match its hash", session.hash.substring(0, 12),
                        player.getGameProfile().getName());
                reply(player, session.hash, FileStatusPayload.REFUSED, "bad_hash");
            }
        } catch (IOException e) {
            UPLOADS.remove(player.getUUID());
            session.abort();
            LOGGER.warn("File sharing: could not store an upload", e);
            reply(player, session.hash, FileStatusPayload.REFUSED, "error");
        }
    }

    // ---- downloads ----

    public static void onRequest(ServerPlayer player, FileRequestPayload request) {
        SharedFileCache cache = SharedFileCache.get();
        String why = cache == null ? "off" : refusal(player);
        if (why != null) {
            reply(player, request.hash(), FileStatusPayload.REFUSED, why);
            return;
        }
        if (!DockQueues.mayDownload(player, request.hash())) {
            reply(player, request.hash(), FileStatusPayload.REFUSED, "not_here");
            return;
        }
        SharedFileCache.Info info = cache.info(request.hash());
        if (info == null) {
            reply(player, request.hash(), FileStatusPayload.NOT_YET, "");
            return;
        }
        if (request.offset() < 0 || request.offset() > info.size()) {
            reply(player, request.hash(), FileStatusPayload.REFUSED, "bad_data");
            return;
        }
        cache.touch(request.hash());
        DOWNLOADS.put(player.getUUID(), new Download(info.hash(), info.size(), cache.pathOf(info), request.offset()));
        LOGGER.info("File sharing: sending {} to {} from byte {}", info.hash().substring(0, 12),
                player.getGameProfile().getName(), request.offset());
    }

    // ---- events ----

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        SharedFileCache.start(server.getWorldPath(LevelResource.ROOT).resolve("icraft").resolve("shared"));
        DockQueues.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (UploadSession s : UPLOADS.values()) s.abort();
        UPLOADS.clear();
        DOWNLOADS.clear();
        DockQueues.clear();
        SharedFileCache.stop();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UploadSession s = UPLOADS.remove(event.getEntity().getUUID());
        if (s != null) s.abort();
        DOWNLOADS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        SharedFileCache cache = SharedFileCache.get();
        if (cache == null) return;

        // send the next pieces of the files being downloaded
        Iterator<Map.Entry<UUID, Download>> it = DOWNLOADS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Download> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Download d = entry.getValue();
            if (player == null) {
                it.remove();
                continue;
            }
            try (RandomAccessFile file = new RandomAccessFile(d.file.toFile(), "r")) {
                for (int i = 0; i < CHUNKS_PER_TICK && d.offset < d.total; i++) {
                    int n = (int) Math.min(FileDataPayload.MAX_CHUNK, d.total - d.offset);
                    byte[] bytes = new byte[n];
                    file.seek(d.offset);
                    file.readFully(bytes);
                    PacketDistributor.sendToPlayer(player, new FileDataPayload(d.hash, d.total, d.offset, bytes));
                    d.offset += n;
                }
            } catch (IOException e) {
                LOGGER.warn("File sharing: could not read {} to send it", d.hash, e);
                reply(player, d.hash, FileStatusPayload.REFUSED, "error");
                it.remove();
                continue;
            }
            if (d.offset >= d.total) it.remove();
        }

        // uploads that stopped coming
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, UploadSession>> up = UPLOADS.entrySet().iterator();
        while (up.hasNext()) {
            UploadSession s = up.next().getValue();
            if (now - s.lastDataMillis > UPLOAD_TIMEOUT_MS) {
                LOGGER.info("File sharing: upload {} timed out", s.hash.substring(0, 12));
                s.abort();
                up.remove();
            }
        }

        // tidy up now and then
        if (++cleanupTimer >= CLEANUP_TICKS) {
            cleanupTimer = 0;
            cache.makeRoom(0, ageMillis(), (long) ServerConfig.MAX_CACHE_SIZE_MB.get() * 1024 * 1024, DockQueues.allHashes());
        }
    }

    private static long ageMillis() {
        return ServerConfig.CACHE_DAYS.get() * 86_400_000L;
    }
}
