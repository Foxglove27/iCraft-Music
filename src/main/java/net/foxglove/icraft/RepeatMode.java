package net.foxglove.icraft;

/** What happens when a track ends. Plain enum with no Minecraft imports: the config spec uses it on servers too. */
public enum RepeatMode {
    OFF,   // stop after the last track
    LIST,  // go back to the start of the list
    ONE;   // play the same track again

    /** The next mode when the setting is clicked: off, list, one, off... */
    public RepeatMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
