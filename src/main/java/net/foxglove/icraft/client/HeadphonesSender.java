package net.foxglove.icraft.client;

import net.foxglove.icraft.item.IpodColor;
import net.foxglove.icraft.net.ListeningPayload;

/**
 * Tells the server when music starts or stops playing on this player (and in which iPod colour), so other players
 * see headphones on them (B18). Sends only when the answer changes.
 */
public final class HeadphonesSender {
    private static int sent = -1; // what the server knows: -1 = not listening

    private HeadphonesSender() {}

    /** Called every client tick. */
    static void tick() {
        IpodColor color = null;
        if (IpodPlayer.playsOnYou()) color = IpodPlayer.playingColor();
        if (color == null && DockClientPlayer.playsOnYou()) color = DockClientPlayer.playingColor();
        int want = color == null ? -1 : color.ordinal();
        if (want != sent && ClientNet.connected()) {
            ClientNet.send(new ListeningPayload(want));
            sent = want;
        }
    }

    /** Joined or left a world, or respawned: the server has forgotten the state, so send it again when needed. */
    public static void reset() {
        sent = -1;
    }
}
