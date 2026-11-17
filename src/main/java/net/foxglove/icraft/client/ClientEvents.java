package net.foxglove.icraft.client;

import net.foxglove.icraft.ClientConfig;
import net.foxglove.icraft.iCraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
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
        net.foxglove.icraft.client.audio.CoverArt.clear();
    }

    // While iCraft music plays for the player, the game's own background music (source MUSIC) is not started.
    // Cancelling the sound is enough: the music manager's own timer ends it and tries again later, so it comes
    // back by itself once the iPod or dock stops. Our own sounds are RECORDS and never affected.
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound != null && sound.getSource() == SoundSource.MUSIC && silenceBackgroundMusic()
                && (IpodPlayer.isListening() || DockClientPlayer.hasActiveSounds())) {
            event.setSound(null);
        }
    }

    private static boolean silenceBackgroundMusic() {
        try {
            return ClientConfig.SILENCE_BACKGROUND_MUSIC.get();
        } catch (IllegalStateException e) {
            return false; // the config is not loaded yet
        }
    }

    // Once per client tick: notices when a track has finished so the next one can start
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IpodPlayer.clientTick();
        DockControl.tick();
        ModKeyMappings.tick(net.minecraft.client.Minecraft.getInstance().screen != null);
    }
}