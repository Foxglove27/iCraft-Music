package net.foxglove.icraft.client;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.client.screen.IpodScreen;
import net.foxglove.icraft.item.IpodColor;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class ClientHooks {
    /** Opens the screen of an iPod the player holds. It plays in the player's ears. */
    public static void openIpodScreen(ItemStack ipod) {
        openScreen(ipod, null, false);
    }

    /** Opens the screen of the iPod that sits in a dock. It plays through the dock's speakers. */
    public static void openDockScreen(DockBlockEntity dock) {
        Level level = dock.getLevel();
        if (level == null) return;
        openScreen(dock.getIpod(), GlobalPos.of(level.dimension(), dock.getBlockPos()), !dock.getSpeakers().isEmpty());
    }

    private static void openScreen(ItemStack ipod, @Nullable GlobalPos dock, boolean playsThroughSpeakers) {
        // the GUI skin depends on the iPod's colour
        IpodColor color = ipod.getItem() instanceof IpodItem item ? item.getColor() : IpodColor.WHITE;
        Minecraft.getInstance().setScreen(new IpodScreen(
                IpodItem.getDiscs(ipod),
                LocalMusic.albums(),
                LocalMusic.books(),
                LocalMusic.podcasts(),
                playsThroughSpeakers,
                dock,
                color.guiTexture(),
                ipod.get(ModDataComponents.IPOD_ID.get())));
    }

    public static void discRemoved(ItemStack disc) {
        IpodPlayer.stopIfPlaying(disc);
    }
}