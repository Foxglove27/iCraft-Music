package net.foxglove.icraft.client.audio;

import com.mojang.blaze3d.audio.Channel;
import net.foxglove.icraft.iCraft;
import net.foxglove.icraft.client.DockClientPlayer;
import net.foxglove.icraft.client.IpodPlayer;
import net.foxglove.icraft.client.IpodSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes the world quieter while iCraft music plays on the player (a held iPod, or a dock without speakers in the
 * owner's ears), like wearing headphones: blocks, steps, mobs, weather and ambience go down to the level chosen in the
 * iPod's Settings ({@link IpodSettings#worldSounds()}), with a short fade. Jukeboxes and docks' speakers too. The
 * game's music, voice, UI clicks and the music in the player's own ears are never touched, and nothing goes to the
 * server. The factor is applied to the channel after the engine's volume (which is clamped to 1), because a speaker's
 * volume of 2 or 4 only sets how far it carries: scaling that number would change nothing audible.
 *
 * <p>Two places set the volume: a sound that starts is turned down right away on the sound thread (the events below
 * come right after the engine started it), and every client tick the sounds that are already playing are set again
 * (for the fade, and because the engine resets the volume of long, "tickable" sounds every tick).
 */
@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public final class WorldSoundFader {
    private static final Set<SoundSource> WORLD = EnumSet.of(SoundSource.BLOCKS, SoundSource.PLAYERS,
            SoundSource.NEUTRAL, SoundSource.HOSTILE, SoundSource.AMBIENT, SoundSource.WEATHER,
            SoundSource.RECORDS); // jukeboxes; our own sounds are RECORDS too, affects() decides for them
    private static final float STEP = 0.05F; // per tick: from full to 30 % in about 0.7 s

    private static volatile float factor = 1.0F; // read on the sound thread
    private static boolean applied;              // the playing sounds still carry a factor below 1

    private WorldSoundFader() {}


    /** Once per client tick: moves the factor toward its target and sets the volume of the sounds that play. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        float target = (IpodPlayer.playsOnYou() || DockClientPlayer.playsOnYou())
                ? IpodSettings.worldSounds().volume() : 1.0F;
        float now = factor;
        if (now < target) now = Math.min(target, now + STEP);
        else if (now > target) now = Math.max(target, now - STEP);
        factor = now;
        if (now >= 1.0F && !applied) return; // nothing to do, nothing to undo
        SoundEngine engine = mc.getSoundManager().soundEngine;
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : engine.instanceToChannel.entrySet()) {
            SoundInstance sound = entry.getKey();
            if (!affects(sound)) continue;
            float volume = engine.calculateVolume(sound) * now;
            entry.getValue().execute(channel -> channel.setVolume(volume));
        }
        applied = now < 1.0F; // once back at full, every sound got its real volume once
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundSourceEvent event) {
        turnDown(event.getEngine(), event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onPlayStreaming(PlayStreamingSourceEvent event) {
        turnDown(event.getEngine(), event.getSound(), event.getChannel());
    }

    // On the sound thread, right after the engine started the sound with its normal volume
    private static void turnDown(SoundEngine engine, SoundInstance sound, Channel channel) {
        float now = factor;
        if (now >= 1.0F || !affects(sound)) return;
        channel.setVolume(engine.calculateVolume(sound) * now);
    }

    private static boolean affects(SoundInstance sound) {
        // our own sounds: only the ones from a speaker (docks); the music in the player's ears stays as it is
        if (sound instanceof IpodSoundInstance ipod) return ipod.isPositional();
        return WORLD.contains(sound.getSource());
    }
}
