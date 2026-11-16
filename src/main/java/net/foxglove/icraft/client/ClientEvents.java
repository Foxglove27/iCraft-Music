package net.foxglove.icraft.client;

import net.foxglove.icraft.iCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IpodPlayer.stop();
    }

    // Once per client tick: notices when a track has finished so the next one can start
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IpodPlayer.clientTick();
    }
}