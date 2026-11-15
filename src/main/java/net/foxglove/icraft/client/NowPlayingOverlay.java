package net.foxglove.icraft.client;

import net.foxglove.icraft.iCraft;
import net.foxglove.icraft.client.audio.CoverArt;
import net.foxglove.icraft.item.ModItems;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.nio.file.Path;

@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public class NowPlayingOverlay {
    private static final int W = 150;      // width of the panel
    private static final int H = 34;       // height of the panel
    private static final int MARGIN = 6;   // gap to the edge of the screen
    private static final int THUMB = 24;   // size of the picture on the left

    private static ItemStack ipodIcon;     // shown when a song has no cover

    // Tells Minecraft to draw our layer on top of the rest of the HUD
    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "now_playing"),
                NowPlayingOverlay::render);
    }

    // Called every frame
    private static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !IpodPlayer.hasTrack()) return; // F1 hides it, and so does stopping

        Font font = mc.font;
        int x = g.guiWidth() - W - MARGIN;   // bottom-right corner
        int y = g.guiHeight() - H - MARGIN;

        // the panel
        g.fill(x, y, x + W, y + H, 0x80101014);

        // the picture on the left
        drawThumb(g, x + 5, y + (H - THUMB) / 2);

        // song name and album, to the right of the picture
        int textX = x + 5 + THUMB + 5;
        int maxWidth = W - (textX - x) - 16;
        g.drawString(font, fit(font, IpodPlayer.getNowPlaying().getString(), maxWidth),
                textX, y + 7, 0xFFFFFFFF, true);
        g.drawString(font, fit(font, IpodPlayer.getNowPlayingSubtitle().getString(), maxWidth),
                textX, y + 19, 0xFFAAAAAA, false);

        // play or pause symbol in the top-right corner of the panel
        drawStatusIcon(g, x + W - 11, y + 7);
    }

    private static void drawThumb(GuiGraphics g, int x, int y) {
        Path cover = IpodPlayer.getNowPlayingCover();
        ResourceLocation texture = cover != null ? CoverArt.get(cover) : null;
        if (texture != null) {
            g.blit(texture, x, y, THUMB, THUMB, 0, 0, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE);
            return;
        }

        // no cover: show the disc that is playing, or else the iPod itself
        g.fill(x, y, x + THUMB, y + THUMB, 0xFF3A3A3A);
        ItemStack icon = IpodPlayer.getNowPlayingDisc();
        if (icon.isEmpty()) {
            if (ipodIcon == null) {
                ipodIcon = new ItemStack(ModItems.IPOD.get());
            }
            icon = ipodIcon;
        }
        float scale = THUMB / 16.0F;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0F);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
    }

    // A small triangle while playing, two bars while paused
    private static void drawStatusIcon(GuiGraphics g, int x, int y) {
        int color = 0xFFFFFFFF;
        if (IpodPlayer.isPaused()) {
            g.fill(x, y, x + 2, y + 5, color);
            g.fill(x + 3, y, x + 5, y + 5, color);
        } else {
            for (int i = 0; i < 5; i++) {
                int width = Math.min(i, 4 - i) + 1;
                g.fill(x, y + i, x + width, y + i + 1, color);
            }
        }
    }

    // Cuts text short with "..." so it fits in the given width
    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }
}