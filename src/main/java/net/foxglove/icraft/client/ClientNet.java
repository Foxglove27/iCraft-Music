package net.foxglove.icraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sending to the server from client code. {@link PacketDistributor#sendToServer} throws a NullPointerException when
 * the client has no connection (it left the world, the server stopped or restarted), which crashed the game from a
 * tick handler. This sends only while connected.
 */
public final class ClientNet {
    private ClientNet() {}

    /** True if the player is connected to a server (or a singleplayer world). */
    public static boolean connected() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.level != null && mc.player != null;
    }

    /** Sends the message; returns false (and sends nothing) when there is no connection. */
    public static boolean send(CustomPacketPayload payload) {
        if (Minecraft.getInstance().getConnection() == null) return false;
        PacketDistributor.sendToServer(payload);
        return true;
    }
}
