package net.foxglove.icraft.client.screen;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Scrolling text that is too wide for its space: it waits, slides left until its end is visible, waits again and
 * starts over. Used by the selected row of the iPod screen and by the now-playing panel.
 */
public final class Marquee {
    private static final long PAUSE_MS = 800;
    private static final double PX_PER_SECOND = 25;

    private Marquee() {}

    /** How far (in pixels) text that is {@code overflow} pixels too wide is moved left now, {@code startMs} = when it began. */
    public static int offset(long startMs, int overflow) {
        long scrollMs = (long) (overflow / PX_PER_SECOND * 1000);
        long cycle = PAUSE_MS + scrollMs + PAUSE_MS;
        long t = (Util.getMillis() - startMs) % cycle;
        if (t < PAUSE_MS) return 0;
        if (t >= PAUSE_MS + scrollMs) return overflow;
        return (int) ((t - PAUSE_MS) * overflow / scrollMs);
    }

    /**
     * Clips drawing to a rectangle. In this Minecraft version GuiGraphics.enableScissor takes plain screen coordinates
     * and ignores the scaling of the pose stack (the iPod is drawn scaled), so the rectangle is transformed here.
     * Call {@code g.disableScissor()} afterwards.
     */
    public static void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        Matrix4f m = g.pose().last().pose();
        Vector3f a = m.transformPosition(new Vector3f(x1, y1, 0));
        Vector3f b = m.transformPosition(new Vector3f(x2, y2, 0));
        g.enableScissor(Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)),
                Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y)));
    }

    /**
     * Scrolling for text drawn in the world (the label above a dock), where drawing cannot be clipped: instead of
     * sliding the whole text, it returns the part that is visible now, moving by whole characters. Same pause, slide,
     * pause cycle as {@link Line}; starts over when the text changes.
     */
    public static final class Clip {
        private String text = "";
        private long start;

        /** The part of {@code value} to draw now so that it fits into {@code width} pixels. */
        public String visible(Font font, String value, int width) {
            if (!value.equals(text)) {
                text = value;
                start = Util.getMillis();
            }
            int overflow = font.width(value) - width;
            if (overflow <= 0) return value;
            int skip = font.plainSubstrByWidth(value, offset(start, overflow)).length(); // characters already scrolled past
            return font.plainSubstrByWidth(value.substring(skip), width);
        }
    }

    /**
     * One line of text that scrolls when it does not fit. It remembers its text, so the scroll starts over when the
     * text changes (a new song).
     */
    public static final class Line {
        private String text = "";
        private long start;

        /** Draws {@code value} at x, y within {@code width} pixels: still if it fits, scrolling (clipped) if not. */
        public void draw(GuiGraphics g, Font font, String value, int x, int y, int width, int color, boolean shadow) {
            if (!value.equals(text)) {
                text = value;
                start = Util.getMillis();
            }
            int overflow = font.width(value) - width;
            if (overflow <= 0) {
                g.drawString(font, value, x, y, color, shadow);
                return;
            }
            scissor(g, x, y - 1, x + width, y + font.lineHeight + 1);
            g.drawString(font, value, x - offset(start, overflow), y, color, shadow);
            g.disableScissor();
        }
    }
}
