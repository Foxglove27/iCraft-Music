package net.foxglove.icraft.block;

import net.foxglove.icraft.item.IpodItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a docking station holds: the iPod that sits in it and the speakers it plays through.
 * Both are sent to the clients (the client that plays the music needs them).
 */
public class DockBlockEntity extends BlockEntity {
    public static final int MAX_SPEAKERS = 4;
    /** A speaker has to be this close to the dock (in blocks) to be added. */
    public static final double MAX_SPEAKER_DISTANCE = 32;

    private ItemStack ipod = ItemStack.EMPTY;
    /**
     * One speaker slot: a single speaker (its position) or a multiblock (its lowest corner). The kind is fixed
     * when the speaker is added, so a broken multiblock is not mistaken for a single speaker.
     */
    public record Slot(BlockPos anchor, boolean multiblock) {}

    private final List<Slot> speakers = new ArrayList<>();

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

    public void setIpod(ItemStack stack) {
        ipod = stack;
        changed();
    }

    /** Removes and returns the docked iPod. */
    public ItemStack takeIpod() {
        ItemStack out = ipod;
        ipod = ItemStack.EMPTY;
        changed();
        return out;
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

    /** Every half second: forget speakers that were broken, and tell the people nearby. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DockBlockEntity dock) {
        if (level.getGameTime() % 10 != 0 || dock.speakers.isEmpty()) return;

        int before = dock.speakers.size();
        dock.speakers.removeIf(slot -> level.hasChunkAt(slot.anchor()) && !dock.isValid(level, slot));
        int removed = before - dock.speakers.size();
        if (removed == 0) return;

        dock.changed();
        for (Player player : level.players()) {
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= 16 * 16) {
                player.displayClientMessage(Component.translatable("message.icraft.dock.speaker_lost",
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ipod = tag.contains("ipod") ? ItemStack.parse(registries, tag.getCompound("ipod")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
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

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
