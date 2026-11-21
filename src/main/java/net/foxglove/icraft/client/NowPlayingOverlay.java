package net.foxglove.icraft.client;

import net.foxglove.icraft.iCraft;
import net.foxglove.icraft.client.audio.CoverArt;
import net.foxglove.icraft.client.screen.Marquee;
import net.foxglove.icraft.client.share.SharedFiles;
import net.foxglove.icraft.item.ModItems;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The now-playing panel in the bottom-right corner: cover, title and album (scrolling when too long) and play/pause.
 * It shows what you really hear: your own music (or your own dock) while it plays, else the dock of another player
 * you are in earshot of (with a line saying whose dock it is); when neither plays, a paused or muted one (your own
 * first); else a shared file that is still on its way. While a shared file downloads,
 * a row at the bottom shows its progress.
 */
@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public class NowPlayingOverlay {
    private static final int W = 150;      // width of the panel
    private static final int H = 34;       // height of the panel without extra rows
    private static final int MARGIN = 6;   // gap to the edge of the screen
    private static final int THUMB = 24;   // size of the picture on the left
    private static final int ROW_H = 12;   // extra height for the "whose dock" row
    private static final int DOWNLOAD_H = 14; // extra height for the download row

    // the lines that scroll when they are too long (each starts over when its text changes)
    private static final Marquee.Line TITLE = new Marquee.Line();
    private static final Marquee.Line SUBTITLE = new Marquee.Line();
    private static final Marquee.Line OWNER = new Marquee.Line();
    private static final Marquee.Line DOWNLOAD = new Marquee.Line();

    private static ItemStack ipodIcon;     // shown when a song has no cover

    /** What the panel shows; built fresh every frame from one of the three sources. */
    private record Shown(String title, String subtitle, int subtitleColor, @Nullable Path cover, ItemStack disc,
                         boolean statusIcon, boolean paused, @Nullable String owner) {}

    // Tells Minecraft to draw our layer on top of the rest of the HUD
    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "now_playing"),
                NowPlayingOverlay::render);
    }

    // Called every frame
    private static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return; // F1 hides it
        SharedFiles.Progress download = SharedFiles.progress();
        Shown shown = whatToShow(download);
        if (shown == null) return; // nothing plays, nothing is heard and nothing is on its way

        Font font = mc.font;
        int h = H + (shown.owner() != null ? ROW_H : 0) + (download != null ? DOWNLOAD_H : 0);
        int x = g.guiWidth() - W - MARGIN;   // bottom-right corner
        int y = g.guiHeight() - h - MARGIN;

        // the panel
        g.fill(x, y, x + W, y + h, 0x80101014);

        // the picture on the left
        drawThumb(g, x + 5, y + (H - THUMB) / 2, shown.cover(), shown.disc());

        // song name and album, to the right of the picture; too long = they scroll
        int textX = x + 5 + THUMB + 5;
        int maxWidth = W - (textX - x) - 16;
        TITLE.draw(g, font, shown.title(), textX, y + 7, maxWidth, 0xFFFFFFFF, true);
        SUBTITLE.draw(g, font, shown.subtitle(), textX, y + 19, maxWidth, shown.subtitleColor(), false);

        // play or pause symbol in the top-right corner of the panel
        if (shown.statusIcon()) drawStatusIcon(g, x + W - 11, y + 7, shown.paused());

        int rowY = y + H - 2;
        if (shown.owner() != null) { // someone else's dock: say whose it is
            String from = Component.translatable("hud.icraft_music.from", shown.owner()).getString();
            OWNER.draw(g, font, from, x + 5, rowY, W - 10, 0xFFAAAAAA, false);
            rowY += ROW_H;
        }
        if (download != null) drawDownload(g, font, download, x + 5, rowY, W - 10);
    }

    // Picks what the panel shows, or null for no panel
    @Nullable
    private static Shown whatToShow(@Nullable SharedFiles.Progress download) {
        String waiting = Component.translatable("hud.icraft_music.waiting").getString();
        String muted = Component.translatable("screen.icraft_music.ipod.muted").getString();

        // your own music (or a dock you own), and another player's dock you are in earshot of
        Shown own = null;
        boolean ownAudible = false;
        if (IpodPlayer.hasTrack()) {
            boolean isMuted = IpodMute.silences(IpodPlayer.playingIpod());
            own = new Shown(IpodPlayer.getNowPlaying().getString(),
                    isMuted ? muted : IpodPlayer.getNowPlayingSubtitle().getString(),
                    isMuted ? 0xFFFF8080 : 0xFFAAAAAA,
                    IpodPlayer.getNowPlayingCover(), IpodPlayer.getNowPlayingDisc(), true, IpodPlayer.isPaused(), null);
            ownAudible = !isMuted && !IpodPlayer.isPaused();
        }
        Shown dock = null;
        boolean dockAudible = false;
        DockClientPlayer.Heard heard = DockClientPlayer.heard();
        if (heard != null) {
            String subtitle = heard.muted() ? muted : heard.waiting() ? waiting : heard.subtitle().getString();
            dock = new Shown(heard.title().getString(), subtitle, heard.muted() ? 0xFFFF8080 : 0xFFAAAAAA,
                    null, heard.disc(), true, heard.paused(), heard.owner());
            dockAudible = !heard.muted() && !heard.paused();
        }

        // what you really hear comes first: your own while it plays, else the dock; then a paused or muted one
        if (ownAudible) return own;
        if (dockAudible) return dock;
        if (own != null) return own;
        if (dock != null) return dock;

        // 3. only a shared file on its way (the song starts as soon as it is here)
        if (download != null) {
            return new Shown(download.title(), waiting, 0xFFAAAAAA, null, ItemStack.EMPTY, false, false, null);
        }
        return null;
    }

    // "Downloading <title> n%" and a thin bar under it, like the iPod's volume bar
    private static void drawDownload(GuiGraphics g, Font font, SharedFiles.Progress download, int x, int y, int width) {
        int percent = Math.max(0, download.percent());
        String text = Component.translatable("hud.icraft_music.downloading", download.title(), percent).getString();
        DOWNLOAD.draw(g, font, text, x, y, width, 0xFFAAAAAA, false);
        int barY = y + font.lineHeight + 1;
        g.fill(x, barY, x + width, barY + 2, 0x66000000);
        g.fill(x, barY, x + width * percent / 100, barY + 2, 0xFFFFFFFF);
    }

    private static void drawThumb(GuiGraphics g, int x, int y, @Nullable Path cover, ItemStack disc) {
        ResourceLocation texture = cover != null ? CoverArt.get(cover) : null;
        if (texture != null) {
            g.blit(texture, x, y, THUMB, THUMB, 0, 0, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE);
            return;
        }

        // no cover: show the disc that is playing, or else the iPod itself
        g.fill(x, y, x + THUMB, y + THUMB, 0xFF3A3A3A);
        ItemStack icon = disc;
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
    private static void drawStatusIcon(GuiGraphics g, int x, int y, boolean paused) {
        int color = 0xFFFFFFFF;
        if (paused) {
            g.fill(x, y, x + 2, y + 5, color);
            g.fill(x + 3, y, x + 5, y + 5, color);
        } else {
            for (int i = 0; i < 5; i++) {
                int width = Math.min(i, 4 - i) + 1;
                g.fill(x, y + i, x + width, y + i + 1, color);
            }
        }
    }
}
