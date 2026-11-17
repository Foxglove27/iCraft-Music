package net.foxglove.icraft.client;

import net.foxglove.icraft.iCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    // Scan the library in the background as soon as the player is in a world, so the iPod opens with it ready
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        net.foxglove.icraft.client.audio.LibraryCache.refreshAsync();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IpodPlayer.stop();
        DockClientPlayer.stopAll();
    }

    // Once per client tick: notices when a track has finished so the next one can start
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IpodPlayer.clientTick();
        DockControl.tick();
        ModKeyMappings.tick(net.minecraft.client.Minecraft.getInstance().screen != null);
    }
}