package net.foxglove.icraft.client.audio;

/**
 * Which channels of a file a sound plays. Minecraft only places mono sounds in the world (a stereo sound is
 * never quieter with distance), so a speaker always gets one channel: the mix, or the left or right side.
 */
public enum ChannelMode {
    /** The file as it is (stereo stays stereo). Only for sounds in the player's own ears. */
    STEREO,
    /** Left and right mixed to mono. */
    MIX,
    /** Only the left channel, as mono. */
    LEFT,
    /** Only the right channel, as mono. */
    RIGHT;

    /** True if the result is a single channel. */
    public boolean isMono() {
        return this != STEREO;
    }
}
