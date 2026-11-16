package net.foxglove.icraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.foxglove.icraft.iCraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * A simple remote control: keys for play/pause, next and previous. They act on whatever the iPod is
 * playing, wherever it is (in the inventory or in a dock). Rebindable in the controls screen.
 */
@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public class ModKeyMappings {
    private static final String CATEGORY = "key.categories.icraft";

    public static final KeyMapping PLAY_PAUSE = new KeyMapping("key.icraft.play_pause",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_WORLD_1, CATEGORY);
    public static final KeyMapping NEXT = new KeyMapping("key.icraft.next",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, CATEGORY);
    public static final KeyMapping PREVIOUS = new KeyMapping("key.icraft.previous",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(PLAY_PAUSE);
        event.register(NEXT);
        event.register(PREVIOUS);
    }

    /** Called every client tick: runs the actions for the keys pressed since the last tick. */
    static void tick(boolean screenOpen) {
        boolean active = !screenOpen && IpodPlayer.hasTrack();
        while (PLAY_PAUSE.consumeClick()) {
            if (active) IpodPlayer.togglePause();
        }
        while (NEXT.consumeClick()) {
            if (active) IpodPlayer.next();
        }
        while (PREVIOUS.consumeClick()) {
            if (active) IpodPlayer.previous();
        }
    }
}
