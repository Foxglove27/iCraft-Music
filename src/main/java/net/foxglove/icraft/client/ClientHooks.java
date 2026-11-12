package net.foxglove.icraft.client;

import net.foxglove.icraft.client.audio.LocalMusic;
import net.foxglove.icraft.client.screen.IpodScreen;
import net.foxglove.icraft.item.IpodItem;
import net.foxglove.icraft.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public class ClientHooks {
    public static void openIpodScreen(ItemStack ipod) {
        Minecraft.getInstance().setScreen(new IpodScreen(
                IpodItem.getDiscs(ipod),
                LocalMusic.list(),
                ipod.get(ModDataComponents.LINKED_SPEAKER.get())));
    }


    public static void discRemoved(ItemStack disc) {
        IpodPlayer.stopIfPlaying(disc);
    }
}