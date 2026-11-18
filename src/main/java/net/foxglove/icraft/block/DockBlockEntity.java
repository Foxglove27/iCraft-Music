package net.foxglove.icraft.block;

import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.ServerConfig;
import net.foxglove.icraft.net.DockControlPayload;
import net.foxglove.icraft.net.DockQueuePayload;
import net.foxglove.icraft.net.share.DockQueues;
import net.foxglove.icraft.net.share.QueueEntry;
import net.foxglove.icraft.net.share.ShareServer;
import net.foxglove.icraft.net.share.SharedFileCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * What a docking station holds: the iPod that sits in it and the speakers it plays through.
 * Both are sent to the clients (the client that plays the music needs them).
 */
public class DockBlockEntity extends BlockEntity {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public static final int MAX_SPEAKERS = 4;
    /** A speaker has to be this close to the dock (in blocks) to be added. */
    public static final double MAX_SPEAKER_DISTANCE = 32;
    /** The owner can control the dock from this far away (in blocks). */
    public static final double CONTROL_DISTANCE = 64;

    private ItemStack ipod = ItemStack.EMPTY;
    /**
     * One speaker slot: a single speaker (its position) or a multiblock (its lowest corner). The kind is fixed
     * when the speaker is added, so a broken multiblock is not mistaken for a single speaker.
     */
    public record Slot(BlockPos anchor, boolean multiblock) {}

    private final List<Slot> speakers = new ArrayList<>();

    // The player who docked the iPod: only they control it. Cleared when the iPod is taken out.
    @Nullable
    private UUID owner;
    private String ownerName = "";
    // What the dock plays from its disc list (null = nothing). Run by the server, copied to every client.
    @Nullable
    private DockPlayback playback;
    // The tracks of the list: the iPod's discs, or (file sharing) the owner's own files held on the server
    private final List<QueueEntry> queue = new ArrayList<>();
    private boolean filesMode;
    // The owner plays one of their own files at this dock: only they hear it, but everybody sees the notes
    private boolean externalPlaying;
    // For the client's own bookkeeping (what it is playing for this dock); never saved or sent
    @Nullable
    public Object clientData;

    public DockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DOCK.get(), pos, state);
    }

    // ---- the docked iPod ----

    public boolean hasIpod() {
        return !ipod.isEmpty() && ipod.getItem() instanceof IpodItem;
    }

    public ItemStack getIpod() {
        return ipod;
    }

    /** Docks an iPod. {@code owner} is the player who docked it. */
    public void setIpod(ItemStack stack, Player owner) {
        ipod = stack;
        this.owner = owner.getUUID();
        this.ownerName = owner.getGameProfile().getName();
        playback = null;
        externalPlaying = false;
        clearQueue();
        changed();
    }

    /** Removes and returns the docked iPod. The music stops and the dock has no owner any more. */
    public ItemStack takeIpod() {
        ItemStack out = ipod;
        ipod = ItemStack.EMPTY;
        owner = null;
        ownerName = "";
        playback = null;
        externalPlaying = false;
        clearQueue();
        changed();
        return out;
    }

    // ---- owner and playback ----

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public boolean isOwner(Player player) {
        return owner != null && owner.equals(player.getUUID());
    }

    /** True for the owner, and for anybody while the dock has no owner yet (a dock filled before owners existed). */
    public boolean canControl(Player player) {
        return owner == null || isOwner(player);
    }

    /**
     * A dock that holds an iPod but has no owner (it was docked by an older version) belongs to the first
     * player who uses it. Server side.
     */
    public void claimIfUnowned(Player player) {
        if (owner == null && hasIpod() && level != null && !level.isClientSide()) {
            owner = player.getUUID();
            ownerName = player.getGameProfile().getName();
            changed();
        }
    }

    @Nullable
    public DockPlayback getPlayback() {
        return playback;
    }

    public boolean isExternalPlaying() {
        return externalPlaying;
    }

    /** True while the dock makes music that people should see notes for. */
    public boolean isMakingMusic() {
        return externalPlaying || (playback != null && playback.isPlaying() && !playback.isPaused());
    }

    /** How many tracks the current list has: the iPod's discs, or the shared files. */
    private int discCount() {
        return filesMode ? queue.size() : IpodItem.getDiscs(ipod).size();
    }

    public List<QueueEntry> getQueue() {
        return new ArrayList<>(queue);
    }

    /** True if the list is the owner's shared files (not the iPod's discs). */
    public boolean isFilesMode() {
        return filesMode;
    }

    private void clearQueue() {
        queue.clear();
        filesMode = false;
        if (level != null && !level.isClientSide()) DockQueues.remove(GlobalPos.of(level.dimension(), worldPosition));
    }

    // a shared file can only play once the server has it
    private boolean playable(int index) {
        if (!filesMode) return true;
        if (index < 0 || index >= queue.size()) return false;
        SharedFileCache cache = SharedFileCache.get();
        return cache != null && cache.info(queue.get(index).hash()) != null;
    }

    /** How long a disc of the docked iPod lasts, in ticks (0 if unknown). Server side. */
    private long discTicks(Level level, int index) {
        if (filesMode) return index >= 0 && index < queue.size() ? queue.get(index).playTicks() : 0;
        List<ItemStack> discs = IpodItem.getDiscs(ipod);
        if (index < 0 || index >= discs.size()) return 0;
        return JukeboxSong.fromStack(level.registryAccess(), discs.get(index)).map(h -> (long) h.value().lengthInTicks()).orElse(0L);
    }

    /**
     * Applies a control message from a player (server side). Only the owner may control the dock, from within 64
     * blocks. Anything else gets a message and changes nothing.
     */
    public void control(Player player, DockControlPayload message) {
        if (level == null || level.isClientSide() || !hasIpod()) return;
        claimIfUnowned(player);
        LOGGER.info("Dock at {}: {} sent action {} (arg {}); owner is {}", worldPosition.toShortString(),
                player.getGameProfile().getName(), message.action(), message.arg(), ownerName);
        if (!isOwner(player)) {
            player.displayClientMessage(Component.translatable("message.icraft_music.dock.not_yours", ownerName), true);
            return;
        }
        if (player.level() != level || player.distanceToSqr(Vec3.atCenterOf(worldPosition)) > CONTROL_DISTANCE * CONTROL_DISTANCE) return;

        long now = level.getGameTime();
        int discs = discCount();
        switch (message.action()) {
            case DockControlPayload.PLAY -> {
                if (message.arg() < 0) return;
                clearQueue(); // the iPod's own discs
                discs = discCount();
                if (message.arg() >= discs) return;
                // a disc carried over from a held iPod starts where it was; kept clear of the end so it cannot end at once
                long length = discTicks(level, message.arg());
                long startAt = length > 40 ? Math.max(0, Math.min(message.startTicks(), length - 40)) : 0;
                playback = DockPlayback.start(discs, message.arg(), message.shuffle(), repeatOf(message.repeat()), now,
                        new Random(), startAt);
                externalPlaying = false;
            }
            case DockControlPayload.EXTERNAL -> {
                externalPlaying = message.arg() != 0;
                if (externalPlaying && playback != null) playback.stop();
            }
            default -> {
                if (playback == null) return;
                switch (message.action()) {
                    case DockControlPayload.PAUSE -> playback.pause(now);
                    case DockControlPayload.RESUME -> playback.resume(now);
                    case DockControlPayload.NEXT -> playback.next(now, this::playable);
                    case DockControlPayload.PREVIOUS -> playback.previous(now, this::playable);
                    case DockControlPayload.SEEK -> playback.seekBy(message.arg(), now, i -> discTicks(level, i), this::playable);
                    case DockControlPayload.STOP -> playback.stop();
                    case DockControlPayload.MODES -> playback.setModes(message.shuffle(), repeatOf(message.repeat()), discs);
                    default -> { return; }
                }
            }
        }
        changed();
    }

    /**
     * The owner starts a list of their own files (file sharing). Server side; everything is checked again:
     * owner, distance, that sharing is allowed for this player, and every entry.
     */
    public void queue(Player player, DockQueuePayload message) {
        if (level == null || level.isClientSide() || !hasIpod() || !(player instanceof ServerPlayer serverPlayer)) return;
        claimIfUnowned(player);
        if (!isOwner(player)) {
            player.displayClientMessage(Component.translatable("message.icraft_music.dock.not_yours", ownerName), true);
            return;
        }
        if (player.level() != level || player.distanceToSqr(Vec3.atCenterOf(worldPosition)) > CONTROL_DISTANCE * CONTROL_DISTANCE) return;
        String why = ShareServer.refusal(serverPlayer);
        if (why != null) {
            player.displayClientMessage(Component.translatable("message.icraft_music.share.refused." + why), true);
            return;
        }

        List<QueueEntry> valid = new ArrayList<>();
        int max = ServerConfig.MAX_QUEUE_ITEMS.get();
        for (QueueEntry e : message.entries()) {
            QueueEntry.validated(e.hash(), e.ext(), e.title(), e.album(), e.startTicks(), e.endTicks(), e.lengthTicks())
                    .ifPresent(valid::add);
            if (valid.size() >= max) break;
        }
        if (valid.isEmpty()) return;

        queue.clear();
        queue.addAll(valid);
        filesMode = true;
        DockQueues.set(GlobalPos.of(level.dimension(), worldPosition), queue);

        long now = level.getGameTime();
        int start = Math.max(0, Math.min(message.startIndex(), valid.size() - 1));
        playback = DockPlayback.start(valid.size(), start, message.shuffle(), repeatOf(message.repeat()), now, new Random(), 0);
        playback.play(start, valid.size(), message.shuffle(), repeatOf(message.repeat()), now,
                Math.max(0, Math.min(message.startOffsetTicks(), valid.get(start).playTicks() - 1)));
        externalPlaying = false;
        LOGGER.info("Dock at {}: {} started a list of {} shared files", worldPosition.toShortString(),
                player.getGameProfile().getName(), valid.size());
        changed();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide() && filesMode) {
            DockQueues.set(GlobalPos.of(level.dimension(), worldPosition), queue); // the files of this queue must stay in the cache
        }
    }

    private static RepeatMode repeatOf(int ordinal) {
        RepeatMode[] modes = RepeatMode.values();
        return modes[Math.max(0, Math.min(ordinal, modes.length - 1))];
    }

    // ---- the speakers ----

    /** The speaker slots (copy). */
    public List<Slot> getSpeakers() {
        return new ArrayList<>(speakers);
    }

    public boolean hasSpeaker(BlockPos anchor) {
        return speakers.stream().anyMatch(slot -> slot.anchor().equals(anchor));
    }

    public boolean isFull() {
        return speakers.size() >= MAX_SPEAKERS;
    }

    /**
     * Adds the slot, or removes it if it is already there. Returns true if it was added. A new multiblock
     * replaces any single speakers inside it, so no block plays twice.
     */
    public boolean toggleSpeaker(BlockPos anchor, @Nullable Multiblock.Result big) {
        if (speakers.removeIf(slot -> slot.anchor().equals(anchor))) {
            changed();
            return false;
        }
        if (big != null) speakers.removeIf(slot -> !slot.multiblock() && big.contains(slot.anchor()));
        speakers.add(new Slot(anchor, big != null));
        changed();
        return true;
    }

    // ---- keeping the list true (server only) ----

    /** Every tick: moves a playing dock on to its next disc. Every half second: forgets speakers that were broken. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DockBlockEntity dock) {
        long now = level.getGameTime();

        if (dock.playback != null && dock.playback.isPlaying() && dock.playback.tick(now, i -> dock.discTicks(level, i), dock::playable)) {
            dock.changed();
        }
        // the owner left or logged out while playing one of their own files: nobody is making that music any more
        if (dock.externalPlaying && now % 20 == 0 && (dock.owner == null || level.getPlayerByUUID(dock.owner) == null)) {
            dock.externalPlaying = false;
            dock.changed();
        }

        if (now % 10 != 0 || dock.speakers.isEmpty()) return;

        int before = dock.speakers.size();
        dock.speakers.removeIf(slot -> level.hasChunkAt(slot.anchor()) && !dock.isValid(level, slot));
        int removed = before - dock.speakers.size();
        if (removed == 0) return;

        dock.changed();
        for (Player player : level.players()) {
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= 16 * 16) {
                player.displayClientMessage(Component.translatable("message.icraft_music.dock.speaker_lost",
                        dock.speakers.size(), MAX_SPEAKERS), true);
            }
        }
    }

    // A single slot needs its speaker; a multiblock slot needs its whole 2x4 wall, still with the same corner
    private boolean isValid(Level level, Slot slot) {
        if (!slot.multiblock()) return level.getBlockState(slot.anchor()).is(ModBlocks.SPEAKER.get());
        Multiblock.Result big = Multiblock.find(level, slot.anchor());
        return big != null && big.anchor().equals(slot.anchor());
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            net.foxglove.icraft.client.ClientHooks.dockRemoved(this); // stop the sounds this dock was playing
        }
        if (level != null && !level.isClientSide()) {
            DockQueues.remove(GlobalPos.of(level.dimension(), worldPosition));
        }
    }

    private void changed() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ---- saving and syncing ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!ipod.isEmpty()) tag.put("ipod", ipod.save(registries));
        ListTag list = new ListTag();
        for (Slot slot : speakers) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", slot.anchor().asLong());
            entry.putBoolean("multiblock", slot.multiblock());
            list.add(entry);
        }
        tag.put("speakers", list);

        if (owner != null) {
            tag.putUUID("owner", owner);
            tag.putString("ownerName", ownerName);
        }
        tag.putBoolean("external", externalPlaying);
        tag.putBoolean("filesMode", filesMode);
        if (!queue.isEmpty()) {
            ListTag items = new ListTag();
            for (QueueEntry e : queue) {
                CompoundTag t = new CompoundTag();
                t.putString("hash", e.hash());
                t.putString("ext", e.ext());
                t.putString("title", e.title());
                t.putString("album", e.album());
                t.putInt("start", e.startTicks());
                t.putInt("end", e.endTicks());
                t.putInt("length", e.lengthTicks());
                items.add(t);
            }
            tag.put("queue", items);
        }
        if (playback != null) {
            DockPlayback.Snapshot s = playback.snapshot();
            CompoundTag p = new CompoundTag();
            p.putIntArray("order", s.order());
            p.putInt("index", s.index());
            p.putBoolean("shuffle", s.shuffle());
            p.putInt("repeat", s.repeat());
            p.putLong("base", s.positionBase());
            p.putLong("changedAt", s.changedAt());
            p.putBoolean("paused", s.paused());
            p.putBoolean("stopped", s.stopped());
            p.putInt("sequence", s.sequence());
            tag.put("playback", p);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ipod = tag.contains("ipod") ? ItemStack.parse(registries, tag.getCompound("ipod")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        externalPlaying = tag.getBoolean("external");
        filesMode = tag.getBoolean("filesMode");
        queue.clear();
        if (tag.contains("queue", Tag.TAG_LIST)) {
            for (Tag raw : tag.getList("queue", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) raw;
                if (!QueueEntry.isSafe(t.getString("hash"), t.getString("ext"))) continue; // never trust a name from a saved or sent tag
                queue.add(new QueueEntry(t.getString("hash"), t.getString("ext"), t.getString("title"), t.getString("album"),
                        t.getInt("start"), t.getInt("end"), t.getInt("length")));
            }
        }
        if (queue.isEmpty()) filesMode = false;
        playback = null;
        if (tag.contains("playback", Tag.TAG_COMPOUND)) {
            CompoundTag p = tag.getCompound("playback");
            int[] order = p.getIntArray("order");
            if (order.length > 0) {
                playback = DockPlayback.restore(new DockPlayback.Snapshot(order, p.getInt("index"), p.getBoolean("shuffle"),
                        p.getInt("repeat"), p.getLong("base"), p.getLong("changedAt"), p.getBoolean("paused"),
                        p.getBoolean("stopped"), p.getInt("sequence")), new Random());
            }
        }
        speakers.clear();
        if (tag.contains("speakers", Tag.TAG_LIST)) {
            for (Tag raw : tag.getList("speakers", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) raw;
                if (speakers.size() < MAX_SPEAKERS) {
                    speakers.add(new Slot(BlockPos.of(entry.getLong("pos")), entry.getBoolean("multiblock")));
                }
            }
        } else { // saved by the first version: a plain list of positions, all single speakers
            for (long packed : tag.getLongArray("speakers")) {
                if (speakers.size() < MAX_SPEAKERS) speakers.add(new Slot(BlockPos.of(packed), false));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    // What the client got from the server, in the log (so a dock that looks empty can be explained)
    private void logReceived(String how) {
        LOGGER.info("Dock at {} ({}): iPod {}, owner {}, speakers {}, playback {}", worldPosition.toShortString(), how,
                hasIpod() ? "docked" : "none", ownerName.isEmpty() ? "none" : ownerName, speakers.size(),
                playback == null ? "none" : (playback.isPlaying() ? (playback.isPaused() ? "paused" : "playing") : "stopped")
                        + " disc " + playback.currentDisc() + " sequence " + playback.sequence());
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        super.onDataPacket(net, packet, registries);
        logReceived("update");
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        logReceived("chunk");
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
