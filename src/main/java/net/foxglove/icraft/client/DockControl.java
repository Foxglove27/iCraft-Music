package net.foxglove.icraft.client;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.block.DockPlayback;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.net.DockControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The owner's side of a dock that plays music discs: sends the control messages and tells the rest of the
 * client (screen, HUD, keys) what the dock is playing. The server does the playing; see {@link DockClientPlayer}.
 */
public final class DockControl {
    // the dock this player last started a disc on
    @Nullable
    private static GlobalPos lastDock;

    // A disc that was playing from a held iPod when it was docked: start it on the dock as soon as the dock has the iPod
    @Nullable
    private static GlobalPos pendingDock;
    private static int pendingDisc;
    private static int pendingTicks;

    private DockControl() {}

    /** Starts disc {@code index} on the dock once it has reported that it holds the iPod (a few ticks). */
    public static void playWhenDocked(GlobalPos dock, int index) {
        pendingDock = dock;
        pendingDisc = index;
        pendingTicks = 60;
    }

    /** Called every client tick. */
    static void tick() {
        if (pendingDock == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null && pendingDock.dimension().equals(mc.level.dimension())
                && mc.level.getBlockEntity(pendingDock.pos()) instanceof DockBlockEntity dock
                && dock.hasIpod() && dock.isOwner(mc.player)) {
            GlobalPos target = pendingDock;
            pendingDock = null;
            play(target, pendingDisc);
        } else if (--pendingTicks <= 0) {
            pendingDock = null;
        }
    }

    // ---- what is going on ----

    /** The dock whose disc list this player is playing right now, or null. */
    @Nullable
    public static DockBlockEntity active() {
        Minecraft mc = Minecraft.getInstance();
        if (lastDock == null || mc.level == null || mc.player == null || !lastDock.dimension().equals(mc.level.dimension())) return null;
        if (!(mc.level.getBlockEntity(lastDock.pos()) instanceof DockBlockEntity dock)) return null;
        DockPlayback pb = dock.getPlayback();
        return dock.hasIpod() && dock.isOwner(mc.player) && pb != null && pb.isPlaying() ? dock : null;
    }

    public static boolean isActive() {
        return active() != null;
    }

    public static boolean isPaused() {
        DockBlockEntity dock = active();
        return dock != null && dock.getPlayback().isPaused();
    }

    /** The index (in the iPod's disc list) of the disc that is playing, or -1. */
    public static int currentDisc() {
        DockBlockEntity dock = active();
        return dock != null ? dock.getPlayback().currentDisc() : -1;
    }

    /** Where the current disc is, in seconds (for skipping). */
    public static ItemStack currentDiscStack() {
        DockBlockEntity dock = active();
        if (dock == null) return ItemStack.EMPTY;
        var discs = IpodItem.getDiscs(dock.getIpod());
        int i = dock.getPlayback().currentDisc();
        return i >= 0 && i < discs.size() ? discs.get(i) : ItemStack.EMPTY;
    }

    public static Component currentName() {
        ItemStack disc = currentDiscStack();
        Minecraft mc = Minecraft.getInstance();
        if (disc.isEmpty() || mc.level == null) return Component.empty();
        return JukeboxSong.fromStack(mc.level.registryAccess(), disc)
                .map(h -> h.value().description()).orElse(disc.getHoverName());
    }

    // ---- sending ----

    private static void send(GlobalPos dock, int action, int arg) {
        PacketDistributor.sendToServer(new DockControlPayload(dock.pos(), action, arg,
                IpodSettings.shuffle(), IpodSettings.repeat().ordinal()));
    }

    /** Starts the docked iPod's disc list at disc {@code index}. Stops anything this player plays locally. */
    public static void play(GlobalPos dock, int index) {
        IpodPlayer.stop(); // a file or disc playing in the player's ears makes way
        lastDock = dock;
        send(dock, DockControlPayload.PLAY, index);
    }

    public static void togglePause() {
        DockBlockEntity dock = active();
        if (dock == null) return;
        send(GlobalPos.of(Minecraft.getInstance().level.dimension(), dock.getBlockPos()),
                dock.getPlayback().isPaused() ? DockControlPayload.RESUME : DockControlPayload.PAUSE, 0);
    }

    public static void next() {
        sendIfActive(DockControlPayload.NEXT, 0);
    }

    public static void previous() {
        sendIfActive(DockControlPayload.PREVIOUS, 0);
    }

    public static void stop() {
        sendIfActive(DockControlPayload.STOP, 0);
    }

    /** Skips within the disc by this many seconds (ignored while paused; the server clamps it). */
    public static void skipSeconds(double seconds) {
        sendIfActive(DockControlPayload.SEEK, (int) Math.round(seconds * 20));
    }

    /** Shuffle or repeat were changed in the settings. */
    public static void modesChanged() {
        sendIfActive(DockControlPayload.MODES, 0);
    }

    private static void sendIfActive(int action, int arg) {
        DockBlockEntity dock = active();
        if (dock == null) return;
        send(GlobalPos.of(Minecraft.getInstance().level.dimension(), dock.getBlockPos()), action, arg);
    }

    /** Tells a dock that the owner plays one of their own files there (so everybody sees the notes). */
    public static void setExternal(GlobalPos dock, boolean playing) {
        PacketDistributor.sendToServer(new DockControlPayload(dock.pos(), DockControlPayload.EXTERNAL,
                playing ? 1 : 0, false, 0));
    }
}
