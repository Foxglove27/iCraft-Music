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
    WHITE("ipod", "white"),
    BLACK("ipod_black", "black"),
    BLUE("ipod_blue", "blue"),
    GREEN("ipod_green", "green"),
    PINK("ipod_pink", "pink"),
    PURPLE("ipod_purple", "purple"),
    RED("ipod_red", "red"),
    YELLOW("ipod_yellow", "yellow"),
    CYAN("ipod_cyan", "cyan");

    private final String itemName;
    private final ResourceLocation guiTexture;

    IpodColor(String itemName, String skinName) {
        this.itemName = itemName;
        this.guiTexture = ResourceLocation.fromNamespaceAndPath(
                iCraft.MOD_ID, "textures/gui/ipod_gui_" + skinName + ".png");
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
