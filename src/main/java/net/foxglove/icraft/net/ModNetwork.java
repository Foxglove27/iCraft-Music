package net.foxglove.icraft.net;

import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.client.ClientHooks;
import net.foxglove.icraft.net.share.ShareServer;
import net.minecraft.server.level.ServerPlayer;
import net.foxglove.icraft.iCraft;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The messages iCraft sends. Registered on the mod event bus on both sides. */
@EventBusSubscriber(modid = iCraft.MOD_ID)
public class ModNetwork {
    private static final String VERSION = "2";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(DockControlPayload.TYPE, DockControlPayload.STREAM_CODEC, ModNetwork::onDockControl);
        registrar.playToServer(EjectDiscPayload.TYPE, EjectDiscPayload.STREAM_CODEC, ModNetwork::onEjectDisc);

        // file sharing
        registrar.playToServer(FileOfferPayload.TYPE, FileOfferPayload.STREAM_CODEC,
                (m, c) -> c.enqueueWork(() -> ShareServer.onOffer((ServerPlayer) c.player(), m)));
        registrar.playToServer(FileChunkPayload.TYPE, FileChunkPayload.STREAM_CODEC,
                (m, c) -> c.enqueueWork(() -> ShareServer.onChunk((ServerPlayer) c.player(), m)));
        registrar.playToServer(FileRequestPayload.TYPE, FileRequestPayload.STREAM_CODEC,
                (m, c) -> c.enqueueWork(() -> ShareServer.onRequest((ServerPlayer) c.player(), m)));
        registrar.playToServer(DockQueuePayload.TYPE, DockQueuePayload.STREAM_CODEC, ModNetwork::onDockQueue);
        registrar.playToClient(FileStatusPayload.TYPE, FileStatusPayload.STREAM_CODEC,
                (m, c) -> c.enqueueWork(() -> ClientHooks.fileStatus(m)));
        registrar.playToClient(FileDataPayload.TYPE, FileDataPayload.STREAM_CODEC,
                (m, c) -> c.enqueueWork(() -> ClientHooks.fileData(m)));
    }

    private static void onEjectDisc(EjectDiscPayload message, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            var stack = player.getItemInHand(message.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            if (stack.getItem() instanceof IpodItem) IpodItem.ejectLastDisc(player, stack);
        });
    }

    private static void onDockQueue(DockQueuePayload message, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.level().isLoaded(message.pos())
                    && player.level().getBlockEntity(message.pos()) instanceof DockBlockEntity dock) {
                dock.queue(player, message);
            }
        });
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
