package net.foxglove.icraft.net;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.iCraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The messages iCraft sends. Registered on the mod event bus on both sides. */
@EventBusSubscriber(modid = iCraft.MOD_ID)
public class ModNetwork {
    private static final String VERSION = "1";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(DockControlPayload.TYPE, DockControlPayload.STREAM_CODEC, ModNetwork::onDockControl);
    }

    private static void onDockControl(DockControlPayload message, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.level().isLoaded(message.pos())
                    && player.level().getBlockEntity(message.pos()) instanceof DockBlockEntity dock) {
                dock.control(player, message);
            }
        });
    }
}
