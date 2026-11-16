package net.foxglove.icraft.client;

import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.client.screen.IpodScreen;
import net.foxglove.icraft.item.IpodColor;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public class ClientHooks {
    public static void openIpodScreen(ItemStack ipod) {
        // the GUI skin depends on the iPod's colour
        IpodColor color = ipod.getItem() instanceof IpodItem item ? item.getColor() : IpodColor.WHITE;
        Minecraft.getInstance().setScreen(new IpodScreen(
                IpodItem.getDiscs(ipod),
                LocalMusic.albums(),
                LocalMusic.books(),
                ipod.has(ModDataComponents.LINKED_SPEAKER.get()),
                color.guiTexture(),
                ipod.get(ModDataComponents.IPOD_ID.get())));
    }


    public static void discRemoved(ItemStack disc) {
        IpodPlayer.stopIfPlaying(disc);
    }
}