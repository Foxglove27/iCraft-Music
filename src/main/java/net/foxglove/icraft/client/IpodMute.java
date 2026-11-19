package net.foxglove.icraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The player's own mute, in two kinds: <b>everything</b> (the key) or <b>single iPods</b> (the double-click on the
 * middle button in an iPod's screen; that iPod's music in the player's ears or from the dock it sits in). A muted
 * sound plays at volume 0 and keeps running, so unmuting continues where it is now. Nothing goes to the server and
 * nobody else is affected. Not saved: it ends when the player unmutes or leaves the world.
 */
public final class IpodMute {
    private static boolean all;
    private static final Set<UUID> IPODS = new HashSet<>();

    private IpodMute() {}

    /** Whether a sound of the iPod {@code ipod} (null = not known) is silent for this player. */
    public static boolean silences(@Nullable UUID ipod) {
        return all || (ipod != null && IPODS.contains(ipod));
    }

    /** The key: unmutes everything if anything is muted, otherwise mutes everything (if something plays). */
    public static void toggleAll() {
        if (all || !IPODS.isEmpty()) {
            all = false;
            IPODS.clear();
            say("message.icraft_music.unmuted_all");
        } else if (IpodPlayer.hasTrack() || DockClientPlayer.anyAudible()) {
            all = true;
            say("message.icraft_music.muted_all");
        }
    }

    /**
     * The double-click in the screen of the iPod {@code ipod}: mutes or unmutes that iPod. While everything is muted
     * it unmutes everything instead. False if there was nothing to do (nothing plays), so the click acts normally.
     */
    public static boolean toggleIpod(@Nullable UUID ipod) {
        if (all) {
            all = false;
            say("message.icraft_music.unmuted_all");
            return true;
        }
        if (ipod == null) return false;
        if (IPODS.remove(ipod)) {
            say("message.icraft_music.unmuted_ipod");
            return true;
        }
        if (!IpodPlayer.hasTrack() && !DockClientPlayer.anyAudible()) return false;
        IPODS.add(ipod);
        say("message.icraft_music.muted_ipod");
        return true;
    }

    /** Leaving or joining a world. */
    public static void reset() {
        all = false;
        IPODS.clear();
    }

    private static void say(String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(key), true);
    }
}
