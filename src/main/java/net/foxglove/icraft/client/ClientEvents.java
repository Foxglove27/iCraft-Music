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
        IpodMute.reset();
        net.foxglove.icraft.client.audio.LibraryCache.refreshAsync();
        net.foxglove.icraft.client.share.SharedFiles.reset();
        net.foxglove.icraft.client.share.FileUploader.reset();
        DockControl.forgetWorld();
        HeadphonesSender.reset();
    }

    // After dying the server has forgotten that music plays on this player: send it again
    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        HeadphonesSender.reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IpodPlayer.stop();
        DockClientPlayer.stopAll();
        IpodMute.reset();
        HeadphonesSender.reset();
        net.foxglove.icraft.client.audio.CoverArt.clear();
        net.foxglove.icraft.client.audio.Wallpaper.clear();
        net.foxglove.icraft.client.share.SharedFiles.reset();
        net.foxglove.icraft.client.share.FileUploader.reset();
        DockControl.forgetWorld();
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

    // Every frame: right after the Esc menu closes, a paused song is paused again at once (see IpodPlayer.keepPaused)
    @SubscribeEvent
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        IpodPlayer.keepPaused();
    }

    // Once per client tick: notices when a track has finished so the next one can start
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IpodPlayer.clientTick();
        net.foxglove.icraft.client.audio.WorldSoundFader.tick();
        DockControl.tick();
        HeadphonesSender.tick();
        net.foxglove.icraft.client.share.SharedFiles.tick();
        ModKeyMappings.tick(net.minecraft.client.Minecraft.getInstance().screen != null);
    }
}