package net.foxglove.icraft;

/** How much the bass is turned up. Plain enum with no Minecraft imports: the config spec uses it on servers too. */
public enum BassBoost {
    OFF(0),
    LOW(4),
    MEDIUM(8),
    HIGH(12);

    private final double db;

    BassBoost(double db) {
        this.db = db;
    }

    /** The boost of the low frequencies in decibels (0 = unchanged). */
    public double db() {
        return db;
    }

    /** The next level when the setting is clicked: off, low, medium, high, off... */
    public BassBoost next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
