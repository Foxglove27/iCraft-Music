package net.foxglove.icraft.client.audio;

import net.foxglove.icraft.iCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;

/**
 * Lets the speakers of one track start on exactly the same sample. The game starts streamed sounds one after
 * another; this event comes on the sound thread right after each start, and {@link IpodSoundInstance} holds the
 * speakers of a group until all are there and then starts them with one OpenAL call. Without it, the same music
 * from several speakers a few milliseconds apart cancels some frequencies and sounds thin ("comb filtering").
 */
@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public final class SpeakerSync {
    private SpeakerSync() {}

    @SubscribeEvent
    public static void onPlayStreaming(PlayStreamingSourceEvent event) {
        if (event.getSound() instanceof IpodSoundInstance sound) {
            sound.onEnginePlay(event.getChannel().source);
        }
    }
}
