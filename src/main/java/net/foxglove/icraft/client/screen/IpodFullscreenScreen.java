package net.foxglove.icraft.client.screen;

import net.foxglove.icraft.client.IpodMute;
import net.foxglove.icraft.client.IpodPlayer;
import net.foxglove.icraft.client.IpodSettings;
import net.foxglove.icraft.client.IpodVolume;
import net.foxglove.icraft.client.audio.CoverArt;
import net.foxglove.icraft.client.audio.Wallpaper;
import net.foxglove.icraft.item.IpodColor;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The fullscreen view of the iPod (B23): a big cover, the title and album, a time bar, large previous / play-pause /
 * next buttons and the volume. Opened by a double-click on the top of the iPod; Esc or a double-click on the top
 * strip goes back to the iPod. Everything it shows comes from the same getters as the now-playing HUD, so it works
 * for files, audiobooks, discs and a dock's list alike.
 */
public class IpodFullscreenScreen extends Screen {
    private static final long DOUBLE_CLICK_MS = 350; // same as on the iPod
    private static final int TOP_STRIP = 20;         // the strip at the top that is double-clicked to go back

    private final Screen parent;
    private final int accent;   // the iPod's colour, made brighter if it would vanish on the dark background

    // where things are, worked out in init() from the window size (so it follows GUI scale and resizing)
    private int coverSize, coverX, coverY;
    private int barX, barY, barW;
    private int buttonsY, buttonSize, prevX, playX, nextX;
    private int volumeX, volumeY, volumeW;

    private long lastTopClick = -1;

    public IpodFullscreenScreen(Screen parent, IpodColor color) {
        super(Component.translatable("screen.icraft_music.fullscreen"));
        this.parent = parent;
        this.accent = readableAccent(color.tint());
    }

    // A very dark colour (the black iPod) would not show on the dark background: use light grey instead
    private static int readableAccent(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r + g + b) / 3 < 0x60 ? 0xFFBBBBBB : 0xFF000000 | rgb;
    }

    // =====================================================================
    // Layout
    // =====================================================================

    @Override
    protected void init() {
        // init() runs when the screen opens and again whenever the window is resized
        coverSize = Math.max(32, Math.min(this.height * 45 / 100, this.width * 40 / 100));
        coverX = (this.width - coverSize) / 2;
        coverY = TOP_STRIP + Math.max(4, this.height / 20);

        int textBottom = coverY + coverSize + 8 + 18 + 12; // title (twice as big) and the album line
        barW = Math.min(this.width - 40, Math.max(coverSize, 240));
        barX = (this.width - barW) / 2;
        barY = textBottom + 6;

        buttonSize = Mth.clamp(this.height / 10, 16, 32);
        buttonsY = barY + 16;
        playX = this.width / 2 - buttonSize / 2;
        prevX = playX - buttonSize * 2;
        nextX = playX + buttonSize * 2;

        volumeW = Math.min(120, barW);
        volumeX = (this.width - volumeW) / 2;
        volumeY = buttonsY + buttonSize + 12;
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    @Override
    public void renderBackground(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // the wallpaper fills the screen if it is switched on and there is a picture; otherwise a dark background
        if (!(IpodSettings.wallpaper() && Wallpaper.draw(g, 0, 0, this.width, this.height, 0xB0))) {
            g.fill(0, 0, this.width, this.height, 0xF0101014);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);

        // the hint in the top strip
        g.drawCenteredString(this.font, Component.translatable("screen.icraft_music.fullscreen.back"),
                this.width / 2, (TOP_STRIP - 8) / 2, 0xFF888888);

        if (!IpodPlayer.hasTrack()) {
            g.drawCenteredString(this.font, Component.translatable("screen.icraft_music.fullscreen.nothing"),
                    this.width / 2, this.height / 2 - 4, 0xFFFFFFFF);
            drawVolume(g);
            return;
        }

        drawCover(g, IpodPlayer.getNowPlayingCover(), IpodPlayer.getNowPlayingDisc());

        // the title twice as big: the pose (the drawing transform) is scaled, so all its coordinates are halved
        int textY = coverY + coverSize + 8;
        String title = fit(IpodPlayer.getNowPlaying().getString(), (this.width - 20) / 2);
        g.pose().pushPose();
        g.pose().translate(this.width / 2.0F, textY, 0);
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.drawCenteredString(this.font, title, 0, 0, 0xFFFFFFFF);
        g.pose().popPose();

        boolean muted = IpodMute.silences(IpodPlayer.playingIpod());
        String subtitle = muted ? Component.translatable("screen.icraft_music.ipod.muted").getString()
                : IpodPlayer.getNowPlayingSubtitle().getString();
        g.drawCenteredString(this.font, fit(subtitle, this.width - 20), this.width / 2, textY + 20, muted ? accent : 0xFFAAAAAA);

        drawTimeBar(g);
        drawButtons(g, mouseX, mouseY);
        drawVolume(g);
    }

    private void drawCover(GuiGraphics g, @Nullable Path cover, ItemStack disc) {
        ResourceLocation texture = cover != null ? CoverArt.get(cover) : null;
        g.fill(coverX - 2, coverY - 2, coverX + coverSize + 2, coverY + coverSize + 2, accent); // a frame in the iPod's colour
        if (texture != null) {
            g.blit(texture, coverX, coverY, coverSize, coverSize, 0, 0, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE);
            return;
        }
        g.fill(coverX, coverY, coverX + coverSize, coverY + coverSize, 0xFF2A2A2A);
        if (!disc.isEmpty()) { // a music disc: its item, scaled up (items are drawn 16 x 16)
            float scale = coverSize * 0.6F / 16.0F;
            g.pose().pushPose();
            g.pose().translate(coverX + coverSize * 0.2F, coverY + coverSize * 0.2F, 0);
            g.pose().scale(scale, scale, 1.0F);
            g.renderItem(disc, 0, 0);
            g.pose().popPose();
        }
    }

    // only when the source knows where it is and how long the track is (not a disc on a held iPod)
    private void drawTimeBar(GuiGraphics g) {
        IpodPlayer.Timeline time = IpodPlayer.timeline();
        if (time == null) return;
        float done = (float) (time.seconds() / time.length());
        g.fill(barX, barY, barX + barW, barY + 3, 0x66FFFFFF);
        g.fill(barX, barY, barX + (int) (barW * done), barY + 3, accent);
        g.drawString(this.font, formatTime(time.seconds()), barX, barY + 5, 0xFFAAAAAA, false);
        String left = "-" + formatTime(time.length() - time.seconds());
        g.drawString(this.font, left, barX + barW - this.font.width(left), barY + 5, 0xFFAAAAAA, false);
    }

    private void drawButtons(GuiGraphics g, int mouseX, int mouseY) {
        drawButtonBack(g, prevX, mouseX, mouseY);
        drawButtonBack(g, playX, mouseX, mouseY);
        drawButtonBack(g, nextX, mouseX, mouseY);

        int s = buttonSize;
        int iconH = s / 2 & ~1;          // the icon height, made even so the triangles have a sharp tip
        int half = iconH / 2;            // a triangle is half as wide as it is tall
        int top = buttonsY + (s - iconH) / 2;
        int skipW = 2 + 1 + half;        // bar + gap + triangle: centred in its button
        // previous: a bar, then a triangle pointing left
        int px = prevX + (s - skipW) / 2;
        g.fill(px, top, px + 2, top + iconH, 0xFFFFFFFF);
        triangle(g, px + 3, top, iconH, false);
        // next: a triangle pointing right, then a bar
        int nx = nextX + (s - skipW) / 2;
        triangle(g, nx, top, iconH, true);
        g.fill(nx + half + 1, top, nx + half + 3, top + iconH, 0xFFFFFFFF);
        // play (a triangle) while paused, pause (two bars) while playing
        if (IpodPlayer.isPaused() || !IpodPlayer.isPlaying()) {
            triangle(g, playX + (s - half) / 2, top, iconH, true);
        } else {
            int bar = Math.max(2, s / 8);
            g.fill(playX + s / 2 - bar - 1, top, playX + s / 2 - 1, top + iconH, 0xFFFFFFFF);
            g.fill(playX + s / 2 + 1, top, playX + s / 2 + bar + 1, top + iconH, 0xFFFFFFFF);
        }
    }

    // a rounded-looking square behind a button; brighter while the mouse is over it
    private void drawButtonBack(GuiGraphics g, int x, int mouseX, int mouseY) {
        boolean hover = onButton(x, mouseX, mouseY);
        int color = hover ? accent : 0x40FFFFFF;
        g.fill(x + 1, buttonsY, x + buttonSize - 1, buttonsY + buttonSize, color);
        g.fill(x, buttonsY + 1, x + buttonSize, buttonsY + buttonSize - 1, color);
    }

    // A filled triangle 'h' pixels tall and h/2 wide, drawn as one 1-pixel line per row (GuiGraphics only fills
    // rectangles). 'right': the tip points right (play, next), else left (previous); x is its left edge.
    private static void triangle(GuiGraphics g, int x, int y, int h, boolean right) {
        int half = h / 2;
        for (int row = 0; row < h; row++) {
            int len = Math.min(row, h - 1 - row) + 1; // grows to the middle, then shrinks again
            int start = right ? x : x + half - len;   // a left-pointing triangle is flat on its right side
            g.fill(start, y + row, start + len, y + row + 1, 0xFFFFFFFF);
        }
    }

    private void drawVolume(GuiGraphics g) {
        Component label = Component.translatable("screen.icraft_music.fullscreen.volume");
        g.drawCenteredString(this.font, label, this.width / 2, volumeY - 10, 0xFF888888);
        g.fill(volumeX, volumeY, volumeX + volumeW, volumeY + 3, 0x66FFFFFF);
        g.fill(volumeX, volumeY, volumeX + (int) (volumeW * IpodVolume.get()), volumeY + 3, 0xFFFFFFFF);
    }

    private static String formatTime(double seconds) {
        int total = (int) Math.max(0, seconds);
        int h = total / 3600, m = (total / 60) % 60, sec = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private String fit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        return this.font.plainSubstrByWidth(text, maxWidth - this.font.width("...")) + "...";
    }

    // =====================================================================
    // Mouse and keyboard
    // =====================================================================

    private boolean onButton(int x, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + buttonSize && mouseY >= buttonsY && mouseY < buttonsY + buttonSize;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        if (mouseY < TOP_STRIP) { // a double-click on the top strip goes back, like the one that opened this view
            long now = Util.getMillis();
            if (lastTopClick >= 0 && now - lastTopClick <= DOUBLE_CLICK_MS) {
                onClose();
            } else {
                lastTopClick = now;
            }
            return true;
        }
        if (onButton(prevX, mouseX, mouseY)) {
            IpodPlayer.previous();
            return true;
        }
        if (onButton(playX, mouseX, mouseY)) {
            IpodPlayer.togglePause();
            return true;
        }
        if (onButton(nextX, mouseX, mouseY)) {
            IpodPlayer.next();
            return true;
        }
        // a click on the volume bar (or a little above or below it) sets the volume there
        if (mouseX >= volumeX && mouseX <= volumeX + volumeW && mouseY >= volumeY - 4 && mouseY <= volumeY + 7) {
            IpodVolume.set((float) ((mouseX - volumeX) / volumeW));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        IpodVolume.set(IpodVolume.get() + (float) scrollY * 0.05F); // anywhere: the volume, like over the wheel
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> IpodPlayer.togglePause();
            case GLFW.GLFW_KEY_LEFT -> IpodPlayer.previous();
            case GLFW.GLFW_KEY_RIGHT -> IpodPlayer.next();
            case GLFW.GLFW_KEY_UP -> IpodVolume.set(IpodVolume.get() + 0.05F);
            case GLFW.GLFW_KEY_DOWN -> IpodVolume.set(IpodVolume.get() - 0.05F);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers); // Esc ends up in onClose()
            }
        }
        return true;
    }

    // Esc and the double-click on the top: back to the iPod (it does not slide in again, see IpodScreen)
    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        IpodVolume.save(); // the volume may have changed here
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the music (and the world) keep running, like on the iPod itself
    }
}
