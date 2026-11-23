package net.foxglove.icraft.item;

import net.foxglove.icraft.iCraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The colours an iPod can have. Each colour is its own item (registered in {@link ModItems})
 * and has its own GUI skin, a 1280x1280 texture in {@code textures/gui/}.
 * All skins must share the layout of the original (display and click wheel in the same place),
 * because {@code IpodScreen} measures that layout once.
 */
public enum IpodColor {
    WHITE("ipod", "white", 0xF2F2F2),
    BLACK("ipod_black", "black", 0x2E2E2E),
    BLUE("ipod_blue", "blue", 0x3C6FD8),
    GREEN("ipod_green", "green", 0x4CAF50),
    PINK("ipod_pink", "pink", 0xF48FB1),
    PURPLE("ipod_purple", "purple", 0x9C5BD0),
    RED("ipod_red", "red", 0xD84040),
    YELLOW("ipod_yellow", "yellow", 0xF2D030),
    CYAN("ipod_cyan", "cyan", 0x30C8D8);

    private final int tint;
    private final String itemName;
    private final ResourceLocation guiTexture;

    IpodColor(String itemName, String skinName, int tint) {
        this.tint = tint;
        this.itemName = itemName;
        this.guiTexture = ResourceLocation.fromNamespaceAndPath(
                iCraft.MOD_ID, "textures/gui/ipod_gui_" + skinName + ".png");
    }

    /** The colour (RGB) the headphones of a player listening to this iPod are tinted with (B18). */
    public int tint() {
        return tint;
    }

    // The registry name of this colour's item (white keeps the original name "ipod").
    public String itemName() {
        return itemName;
    }

    // The GUI skin that is shown when an iPod of this colour is opened.
    public ResourceLocation guiTexture() {
        return guiTexture;
    }
}
