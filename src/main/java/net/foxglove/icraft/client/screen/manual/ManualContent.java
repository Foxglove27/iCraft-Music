package net.foxglove.icraft.client.screen.manual;

import net.foxglove.icraft.block.ModBlocks;
import net.foxglove.icraft.client.ModKeyMappings;
import net.foxglove.icraft.item.IpodColor;
import net.foxglove.icraft.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the iCraft Manual says: its chapters, their pages, and the blocks on each page. <b>Update this (and the lang
 * keys) in the same change as any feature it describes.</b>
 *
 * <p>Lang keys: {@code manual.icraft_music.<chapter>.title} for a chapter, {@code manual.icraft_music.<chapter>.<page>.title}
 * for a page, and {@code manual.icraft_music.<chapter>.<page>.<n>} for its paragraphs and captions.
 */
public final class ManualContent {
    private ManualContent() {}

    public record Page(String chapter, String id, List<ManualBlock> blocks) {
        public Component title() {
            return Component.translatable("manual.icraft_music." + chapter + "." + id + ".title");
        }
    }

    public record Chapter(String id, List<Page> pages) {
        public Component title() {
            return Component.translatable("manual.icraft_music." + id + ".title");
        }
    }

    private static List<Chapter> chapters;

    /** All chapters in order (built once; the text itself is read from the lang files when it is drawn). */
    public static List<Chapter> chapters() {
        if (chapters == null) chapters = build();
        return chapters;
    }

    private static List<Chapter> build() {
        List<Chapter> list = new ArrayList<>();

        list.add(chapter("start",
                page("start", "welcome", text("start.welcome.1"), text("start.welcome.2")),
                page("start", "ipod", new ManualBlock.Recipe("ipod"), text("start.ipod.1")),
                page("start", "wheel", new ManualBlock.Wheel(key("start.wheel.1")), text("start.wheel.2"), text("start.wheel.3"))));

        list.add(chapter("music",
                page("music", "folders", text("music.folders.1"), text("music.folders.2"), text("music.folders.3")),
                page("music", "formats", text("music.formats.1"), text("music.formats.2")),
                page("music", "books", text("music.books.1"), text("music.books.2")),
                page("music", "search", text("music.search.1"), text("music.search.2"))));

        list.add(chapter("discs",
                page("discs", "load", new ManualBlock.Items(() -> List.of(new ItemStack(Items.MUSIC_DISC_CAT),
                        new ItemStack(Items.MUSIC_DISC_13), new ItemStack(Items.MUSIC_DISC_PIGSTEP))),
                        text("discs.load.1"), text("discs.load.3"), text("discs.load.2"))));

        list.add(chapter("dock",
                page("dock", "dock", new ManualBlock.Recipe("dock"), text("dock.dock.1")),
                page("dock", "owner", text("dock.owner.1"), text("dock.owner.2")),
                page("dock", "speakers", new ManualBlock.Recipe("speaker"), text("dock.speakers.1"), text("dock.speakers.2")),
                page("dock", "wall", new ManualBlock.Wall(key("dock.wall.1"),
                        () -> new ItemStack(ModBlocks.SPEAKER.get())), text("dock.wall.2")),
                page("dock", "stereo", text("dock.stereo.1"), text("dock.stereo.2"))));

        list.add(chapter("multiplayer",
                page("multiplayer", "listen", text("multiplayer.listen.1"),
                        new ManualBlock.Text(key("multiplayer.listen.2"),
                                () -> new Object[]{ModKeyMappings.MUTE_FOR_ME.getTranslatedKeyMessage()})),
                page("multiplayer", "share", text("multiplayer.share.1"))));

        list.add(chapter("settings",
                page("settings", "keys", new ManualBlock.Text(key("settings.keys.1"), () -> new Object[]{
                        ModKeyMappings.PLAY_PAUSE.getTranslatedKeyMessage(), ModKeyMappings.NEXT.getTranslatedKeyMessage(),
                        ModKeyMappings.PREVIOUS.getTranslatedKeyMessage(), ModKeyMappings.MUTE_FOR_ME.getTranslatedKeyMessage()}),
                        text("settings.keys.2")),
                page("settings", "ipod", text("settings.ipod.1"), text("settings.ipod.3"), text("settings.ipod.2")),
                page("settings", "colours", new ManualBlock.Items(() -> Arrays.stream(IpodColor.values())
                        .map(c -> new ItemStack(ModItems.IPODS.get(c).get())).toList()), text("settings.colours.1"))));

        return List.copyOf(list);
    }

    private static Chapter chapter(String id, Page... pages) {
        return new Chapter(id, List.of(pages));
    }

    private static Page page(String chapter, String id, ManualBlock... blocks) {
        return new Page(chapter, id, List.of(blocks));
    }

    private static String key(String rest) {
        return "manual.icraft_music." + rest;
    }

    private static ManualBlock text(String rest) {
        return new ManualBlock.Text(key(rest));
    }
}
