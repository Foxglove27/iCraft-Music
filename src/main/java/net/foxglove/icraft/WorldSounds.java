package net.foxglove.icraft;

/**
 * How loud the world (blocks, steps, mobs, weather, ambience) stays while iCraft music plays on the player, like
 * wearing headphones. Plain enum with no Minecraft imports: the config spec uses it on servers too.
 */
public enum WorldSounds {
    NORMAL(1.0F),
    QUIETER(0.7F),
    QUIET(0.5F),
    VERY_QUIET(0.3F);

    private final float volume;

    WorldSounds(float volume) {
        this.volume = volume;
    }

    /** The share of their normal volume the world sounds keep (1 = unchanged). */
    public float volume() {
        return volume;
    }

    /** The next level when the setting is clicked: normal, quieter, quiet, very quiet, normal... */
    public WorldSounds next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
