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
        openScreen(ipod, null, false, true);
    }

    /** Opens the screen of the iPod that sits in a dock. It plays through the dock's speakers. */
    public static void openDockScreen(DockBlockEntity dock) {
        Level level = dock.getLevel();
        if (level == null) return;
        Player player = Minecraft.getInstance().player;
        openScreen(dock.getIpod(), GlobalPos.of(level.dimension(), dock.getBlockPos()), !dock.getSpeakers().isEmpty(),
                player != null && dock.canControl(player));
    }

    private static void openScreen(ItemStack ipod, @Nullable GlobalPos dock, boolean playsThroughSpeakers, boolean dockOwner) {
        // the GUI skin depends on the iPod's colour
        IpodColor color = ipod.getItem() instanceof IpodItem item ? item.getColor() : IpodColor.WHITE;
        LibraryCache.refreshAsync(); // the lists on the screen update themselves when the scan is done
        Minecraft.getInstance().setScreen(new IpodScreen(
                IpodItem.getDiscs(ipod),
                playsThroughSpeakers,
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