package net.foxglove.icraft.client;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.client.audio.LibraryCache;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.client.screen.IpodScreen;
import net.foxglove.icraft.item.IpodColor;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class ClientHooks {
    /** Opens the screen of an iPod the player holds. It plays in the player's ears. */
    public static void openIpodScreen(ItemStack ipod) {
        openScreen(ipod, null, true);
    }

    /**
     * Ctrl + right-click with an iPod in hand: takes its last disc out (the server does it, told by a message;
     * a disc that is playing stops here). Returns false when Ctrl is not held or there is no disc.
     */
    public static boolean ejectDiscWithCtrl(ItemStack ipod, net.minecraft.world.InteractionHand hand) {
        if (!net.minecraft.client.gui.screens.Screen.hasControlDown()) return false;
        java.util.List<ItemStack> discs = IpodItem.getDiscs(ipod);
        if (discs.isEmpty()) return true; // Ctrl was held: nothing to open either
        discRemoved(discs.get(discs.size() - 1));
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new net.foxglove.icraft.net.EjectDiscPayload(hand == net.minecraft.world.InteractionHand.OFF_HAND));
        return true;
    }

    /** Opens the screen of the iPod that sits in a dock. It plays through the dock's speakers. */
    public static void openDockScreen(DockBlockEntity dock) {
        Level level = dock.getLevel();
        if (level == null) return;
        Player player = Minecraft.getInstance().player;
        openScreen(dock.getIpod(), GlobalPos.of(level.dimension(), dock.getBlockPos()),
                player != null && dock.canControl(player));
    }

    private static void openScreen(ItemStack ipod, @Nullable GlobalPos dock, boolean dockOwner) {
        // the GUI skin depends on the iPod's colour
        IpodColor color = ipod.getItem() instanceof IpodItem item ? item.getColor() : IpodColor.WHITE;
        LibraryCache.refreshIfStale(); // the lists on the screen update themselves when the scan is done
        Minecraft.getInstance().setScreen(new IpodScreen(
                IpodItem.getDiscs(ipod),
                dock,
                dockOwner,
                color.guiTexture(),
                ipod.get(ModDataComponents.IPOD_ID.get())));
    }

    /** Client ticker of a dock: follows what the server says the dock plays. */
    public static void dockClientTick(Level level, net.minecraft.core.BlockPos pos,
                                      net.minecraft.world.level.block.state.BlockState state, DockBlockEntity dock) {
        DockClientPlayer.clientTick(level, pos, state, dock);
    }

    /** The player put an iPod into a dock (client side of the click, before the server answers). */
    public static void ipodDocked(GlobalPos dock, ItemStack ipod) {
        IpodPlayer.handOverToDock(dock, ipod.get(ModDataComponents.IPOD_ID.get()), ipod);
    }

    /** The server answered about a file (an upload of ours, or a download we asked for). */
    public static void fileStatus(net.foxglove.icraft.net.FileStatusPayload status) {
        if (!net.foxglove.icraft.client.share.FileUploader.onStatus(status)) {
            net.foxglove.icraft.client.share.SharedFiles.onStatus(status);
        }
    }

    /** A piece of a shared file arrived. */
    public static void fileData(net.foxglove.icraft.net.FileDataPayload data) {
        net.foxglove.icraft.client.share.SharedFiles.onData(data);
    }

    /** The player takes the iPod out of a dock (client side of the click, before the server answers). */
    public static void ipodTakenFromDock(DockBlockEntity dock) {
        IpodPlayer.pickupFromDock(dock);
    }

    /** A dock block entity went away on the client: stop what it was playing. */
    public static void dockRemoved(DockBlockEntity dock) {
        DockClientPlayer.cleanup(dock);
    }

    public static void discRemoved(ItemStack disc) {
        IpodPlayer.stopIfPlaying(disc);
    }
}