package net.foxglove.icraft.client.screen;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The pages of the iCraft Manual. Every page is a lang key {@code manual.icraft_music.<chapter>.<page>} (both
 * counted from 1), so adding a page only needs new keys in the language files. Reading stops at the first missing
 * chapter, or at the first missing page of a chapter.
 */
public class ManualPages {
    public static List<Component> build() {
        List<Component> pages = new ArrayList<>();
        for (int chapter = 1; ; chapter++) {
            int before = pages.size();
            for (int page = 1; ; page++) {
                String key = "manual.icraft_music." + chapter + "." + page;
                if (!I18n.exists(key)) break;
                pages.add(Component.translatable(key));
            }
            if (pages.size() == before) break; // no page 1: no such chapter, so this was the last one
        }
        if (pages.isEmpty()) pages.add(Component.literal("(no pages)"));
        return pages;
    }
}
