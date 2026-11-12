package net.foxglove.icraft.client;

import net.foxglove.icraft.client.screen.IpodScreen;
import net.minecraft.client.Minecraft;

public class ClientHooks {
    public static void openIpodScreen() {
        Minecraft.getInstance().setScreen(new IpodScreen());
    }
}