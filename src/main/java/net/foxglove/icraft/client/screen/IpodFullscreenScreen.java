package net.foxglove.icraft.client.screen;

import net.foxglove.icraft.RepeatMode;
import net.foxglove.icraft.client.IpodMute;
import net.foxglove.icraft.client.IpodPlayer;
import net.foxglove.icraft.client.IpodSettings;
import net.foxglove.icraft.client.IpodVolume;
import net.foxglove.icraft.client.audio.CoverArt;
import net.foxglove.icraft.client.audio.Wallpaper;
import net.foxglove.icraft.item.IpodColor;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The fullscreen view of the iPod (B23): a big cover, the title and album, a time bar, large shuffle / previous /
 * play-pause / next / repeat buttons and the volume, a search bar (top left) and the wallpaper picker (top right).
 * Opened by a double-click on the top of the iPod; Esc or a double-click on the top strip goes back to the iPod.
 * Everything it shows comes from the same getters as the now-playing HUD, so it works for files, audiobooks, discs
 * and a dock's list alike.
 */
public class IpodFullscreenScreen extends Screen {
    private static final long DOUBLE_CLICK_MS = 350; // same as on the iPod
    private static final int TOP_STRIP = 20;         // the strip at the top that is double-clicked to go back
    private static final int BAR_H = 14;             // height of the search box and the picker button
    private static final int RESULT_ROWS = 8;        // search results shown at once (the rest scroll)
    private static final int RESULT_H = 11;
    private static final int PICK_ROWS = 6;          // pictures shown at once in the picker (the rest scroll)
    private static final int PICK_H = 18;            // a picker row: a 16 px thumbnail and its name

    // Holding previous / next skips within the song, a quick click changes track (like the click wheel)
    private enum Hold { NONE, PREVIOUS, NEXT }
    private static final long HOLD_START_MS = 400;
    private static final long HOLD_STEP_MS = 400;
    private static final double HOLD_SKIP_SECONDS = 10;

    private final IpodScreen parent;
    private final int accent;   // the iPod's colour, made brighter if it would vanish on the dark background

    // where things are, worked out in init() from the window size (so it follows GUI scale and resizing)
    private int coverSize, coverX, coverY;
    private int barX, barY, barW;
    private int buttonsY, buttonSize, shuffleX, prevX, playX, nextX, repeatX;
    private int volumeX, volumeY, volumeW;
    private int pickX, pickW, panelX, panelW;

    private long lastTopClick = -1;

    // ---- search (top left) ----
    @Nullable
    private EditBox search;
    private List<IpodScreen.SearchEntry> results = List.of();
    private int resultSelected, resultScroll;

    // ---- wallpaper picker (top right) ----
    private boolean pickerOpen;
    private int pickScroll;

    // ---- hold to skip ----
    private Hold hold = Hold.NONE;
    private long holdStart, holdLastStep;
    private boolean holdSkipped;

    public IpodFullscreenScreen(IpodScreen parent, IpodColor color) {
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

        // five buttons, each two button-widths apart: they must fit into the width as well
        buttonSize = Mth.clamp(Math.min(this.height / 10, this.width / 11), 12, 32);
        buttonsY = barY + 16;
        playX = this.width / 2 - buttonSize / 2;
        prevX = playX - buttonSize * 2;
        nextX = playX + buttonSize * 2;
        shuffleX = prevX - buttonSize * 2;
        repeatX = nextX + buttonSize * 2;

        volumeW = Math.min(120, barW);
        volumeX = (this.width - volumeW) / 2;
        volumeY = buttonsY + buttonSize + 12;

        // the picker button in the top right corner, its panel below it
        pickW = this.font.width(pickerLabel()) + 10;
        pickX = this.width - pickW - 6;
        panelW = Math.min(160, this.width / 2 - 12);
        panelX = this.width - panelW - 6;

        // the search box in the top left corner. init() runs again on a resize and throws the old widgets away,
        // so the typed text is carried over into the new box.
        String typed = search != null ? search.getValue() : "";
        boolean focused = search != null && search.isFocused();
        search = new EditBox(this.font, 6, 3, Math.min(140, this.width / 3), BAR_H,
                Component.translatable("screen.icraft_music.fullscreen.search"));
        search.setHint(Component.translatable("screen.icraft_music.fullscreen.search"));
        search.setMaxLength(IpodScreen.SEARCH_MAX_LENGTH);
        search.setValue(typed);
        search.setResponder(this::searchChanged); // after setValue, so the old results stay as they are
        addRenderableWidget(search);
        if (focused) setFocused(search);
    }

    private Component pickerLabel() {
        return Component.translatable("screen.icraft_music.fullscreen.wallpaper").append(" ▾"); // a small ▾
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
        // super.render draws the background and then the widgets (the search box)
        super.render(g, mouseX, mouseY, partialTick);
        drawTopStrip(g, mouseX, mouseY);

        if (!IpodPlayer.hasTrack()) {
            g.drawCenteredString(this.font, Component.translatable("screen.icraft_music.fullscreen.nothing"),
                    this.width / 2, this.height / 2 - 4, 0xFFFFFFFF);
        } else {
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
        }
        drawVolume(g);

        // the two drop-down panels last, so they lie on top of everything (also above a disc item: z 400)
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        drawResults(g, mouseX, mouseY);
        drawPicker(g, mouseX, mouseY);
        g.pose().popPose();

        drawButtonTooltip(g, mouseX, mouseY);
    }

    // the hint "double-click here to go back" (only where it does not touch the search box or the picker button)
    // and the picker button
    private void drawTopStrip(GuiGraphics g, int mouseX, int mouseY) {
        Component back = Component.translatable("screen.icraft_music.fullscreen.back");
        int half = this.font.width(back) / 2;
        assert search != null;
        if (this.width / 2 - half > search.getX() + search.getWidth() + 6 && this.width / 2 + half < pickX - 6) {
            g.drawCenteredString(this.font, back, this.width / 2, (TOP_STRIP - 8) / 2, 0xFF888888);
        }
        boolean hover = onPickerButton(mouseX, mouseY);
        g.fill(pickX, 3, pickX + pickW, 3 + BAR_H, hover || pickerOpen ? accent : 0x40FFFFFF);
        g.drawCenteredString(this.font, pickerLabel(), pickX + pickW / 2, 3 + (BAR_H - 8) / 2, 0xFFFFFFFF);
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
        drawButtonBack(g, shuffleX, mouseX, mouseY, false);
        drawButtonBack(g, prevX, mouseX, mouseY, hold == Hold.PREVIOUS);
        drawButtonBack(g, playX, mouseX, mouseY, false);
        drawButtonBack(g, nextX, mouseX, mouseY, hold == Hold.NEXT);
        drawButtonBack(g, repeatX, mouseX, mouseY, false);

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
        // shuffle and repeat: white while on, grey while off
        boolean shuffle = IpodSettings.shuffle();
        RepeatMode repeat = IpodSettings.repeat();
        drawPattern(g, SHUFFLE_ICON, shuffleX, shuffle ? 0xFFFFFFFF : 0xFF707070);
        drawPattern(g, REPEAT_ICON, repeatX, repeat != RepeatMode.OFF ? 0xFFFFFFFF : 0xFF707070);
        if (repeat == RepeatMode.ONE) { // a small "1" in the corner of the button
            g.drawString(this.font, "1", repeatX + s - 7, buttonsY + s - 9, accent, false);
        }
    }

    // a rounded-looking square behind a button; in the iPod's colour while the mouse is over it or it is held
    private void drawButtonBack(GuiGraphics g, int x, int mouseX, int mouseY, boolean held) {
        boolean lit = held || (onButton(x, mouseX, mouseY) && !overPanel(mouseX, mouseY));
        int color = lit ? accent : 0x40FFFFFF;
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

    // Two small pictures, '#' = a pixel. Shuffle: two lines that cross, each ending in an arrow to the right.
    private static final String[] SHUFFLE_ICON = {
            ".......#.",
            "##...####",
            "..#.#..#.",
            "...#.....",
            "..#.#..#.",
            "##...####",
            ".......#."};
    // Repeat: a loop with an arrow on its top edge.
    private static final String[] REPEAT_ICON = {
            ".....#...",
            "#######.#",
            "#....#..#",
            "#.......#",
            "#.......#",
            "#########"};

    // draws a pattern centred in the button at x, each '#' as a square of 'unit' pixels
    private void drawPattern(GuiGraphics g, String[] pattern, int x, int color) {
        int unit = Math.max(1, buttonSize * 5 / 8 / pattern[0].length());
        int w = pattern[0].length() * unit, h = pattern.length * unit;
        int left = x + (buttonSize - w) / 2, top = buttonsY + (buttonSize - h) / 2;
        for (int row = 0; row < pattern.length; row++) {
            for (int col = 0; col < pattern[row].length(); col++) {
                if (pattern[row].charAt(col) != '#') continue;
                int px = left + col * unit, py = top + row * unit;
                g.fill(px, py, px + unit, py + unit, color);
            }
        }
    }

    // "Shuffle: On" / "Repeat: List" over the two mode buttons, written like the Settings rows
    private void drawButtonTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!IpodPlayer.hasTrack() || overPanel(mouseX, mouseY)) return;
        Component tip = null;
        if (onButton(shuffleX, mouseX, mouseY)) {
            tip = Component.translatable("screen.icraft_music.ipod.setting",
                    Component.translatable("screen.icraft_music.ipod.shuffle"),
                    Component.translatable(IpodSettings.shuffle() ? "screen.icraft_music.ipod.on" : "screen.icraft_music.ipod.off"));
        } else if (onButton(repeatX, mouseX, mouseY)) {
            tip = Component.translatable("screen.icraft_music.ipod.setting",
                    Component.translatable("screen.icraft_music.ipod.repeat"),
                    Component.translatable("screen.icraft_music.ipod.repeat." + IpodSettings.repeat().name().toLowerCase(Locale.ROOT)));
        }
        if (tip != null) g.renderTooltip(this.font, tip, mouseX, mouseY);
    }

    private void drawVolume(GuiGraphics g) {
        Component label = Component.translatable("screen.icraft_music.fullscreen.volume");
        g.drawCenteredString(this.font, label, this.width / 2, volumeY - 10, 0xFF888888);
        g.fill(volumeX, volumeY, volumeX + volumeW, volumeY + 3, 0x66FFFFFF);
        g.fill(volumeX, volumeY, volumeX + (int) (volumeW * IpodVolume.get()), volumeY + 3, 0xFFFFFFFF);
    }

    // ---- the search results, a list below the search box ----

    private boolean resultsShown() {
        return search != null && search.isFocused() && !search.getValue().isBlank();
    }

    private int resultsX() {
        assert search != null;
        return search.getX();
    }

    private int resultsW() {
        return Math.min(220, this.width - 12);
    }

    private int resultsY() {
        return 3 + BAR_H + 1;
    }

    private int resultRowsShown() {
        return Math.max(1, Math.min(RESULT_ROWS, results.size()));
    }

    private void drawResults(GuiGraphics g, int mouseX, int mouseY) {
        if (!resultsShown()) return;
        int x = resultsX(), y = resultsY(), w = resultsW();
        g.fill(x, y, x + w, y + resultRowsShown() * RESULT_H + 2, 0xF0202024);
        if (results.isEmpty()) {
            g.drawString(this.font, Component.translatable("screen.icraft_music.ipod.no_results"), x + 3, y + 2, 0xFFAAAAAA, false);
            return;
        }
        int hovered = resultAt(mouseX, mouseY);
        for (int row = 0; row < resultRowsShown(); row++) {
            int index = resultScroll + row;
            if (index >= results.size()) break;
            IpodScreen.SearchEntry entry = results.get(index);
            int rowY = y + 1 + row * RESULT_H;
            boolean lit = index == resultSelected || index == hovered;
            if (lit) g.fill(x + 1, rowY, x + w - 1, rowY + RESULT_H, index == resultSelected ? accent : 0x40FFFFFF);
            // the tag ("Song", "Album", ...) on the right, the name in the room that is left
            String tag = Component.translatable("screen.icraft_music.ipod.result_" + entry.tag()).getString();
            int tagW = this.font.width(tag);
            g.drawString(this.font, tag, x + w - 3 - tagW, rowY + 2, 0xFFAAAAAA, false);
            g.drawString(this.font, fit(entry.label(), w - 9 - tagW), x + 3, rowY + 2, 0xFFFFFFFF, false);
        }
    }

    // the result under the mouse, or -1
    private int resultAt(double mouseX, double mouseY) {
        if (!resultsShown() || results.isEmpty()) return -1;
        int x = resultsX(), y = resultsY() + 1;
        if (mouseX < x || mouseX >= x + resultsW() || mouseY < y || mouseY >= y + resultRowsShown() * RESULT_H) return -1;
        int index = resultScroll + (int) ((mouseY - y) / RESULT_H);
        return index < results.size() ? index : -1;
    }

    private boolean overResults(double mouseX, double mouseY) {
        return resultsShown() && mouseX >= resultsX() && mouseX < resultsX() + resultsW()
                && mouseY >= resultsY() && mouseY < resultsY() + resultRowsShown() * RESULT_H + 2;
    }

    // ---- the wallpaper picker, a list below its button ----

    // the rows of the picker: every picture, then "None" (null)
    private List<Path> pickRows() {
        return Wallpaper.pictures();
    }

    private int pickRowCount() {
        return pickRows().size() + 1; // + "None"
    }

    private int pickRowsShown() {
        return Math.min(PICK_ROWS, pickRowCount());
    }

    private int pickY() {
        return 3 + BAR_H + 1;
    }

    private void drawPicker(GuiGraphics g, int mouseX, int mouseY) {
        if (!pickerOpen) return;
        List<Path> pictures = pickRows();
        int x = panelX, y = pickY(), w = panelW;
        int extra = pictures.isEmpty() ? 12 : 0; // room for the "put pictures into..." hint
        g.fill(x, y, x + w, y + extra + pickRowsShown() * PICK_H + 2, 0xF0202024);
        if (pictures.isEmpty()) {
            g.drawString(this.font, fit(Component.translatable("screen.icraft_music.fullscreen.wallpaper.empty").getString(), w - 6),
                    x + 3, y + 3, 0xFFAAAAAA, false);
        }
        Path chosen = IpodSettings.wallpaper() ? Wallpaper.find() : null;
        int hovered = pickAt(mouseX, mouseY);
        for (int row = 0; row < pickRowsShown(); row++) {
            int index = pickScroll + row;
            if (index >= pickRowCount()) break;
            Path picture = index < pictures.size() ? pictures.get(index) : null; // the last row is "None"
            int rowY = y + extra + 1 + row * PICK_H;
            boolean isChosen = picture == null ? !IpodSettings.wallpaper() : picture.equals(chosen);
            if (isChosen || index == hovered) {
                g.fill(x + 1, rowY, x + w - 1, rowY + PICK_H, isChosen ? accent : 0x40FFFFFF);
            }
            int textX = x + 3;
            if (picture != null) {
                // the thumbnail: CoverArt reads it in the background like an album cover (null until it is ready)
                ResourceLocation thumb = CoverArt.get(picture);
                if (thumb != null) {
                    g.blit(thumb, x + 2, rowY + 1, 16, 16, 0, 0, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE);
                } else {
                    g.fill(x + 2, rowY + 1, x + 18, rowY + 17, 0xFF3A3A3A);
                }
                textX = x + 21;
            }
            String name = picture != null ? picture.getFileName().toString()
                    : Component.translatable("screen.icraft_music.fullscreen.wallpaper.none").getString();
            g.drawString(this.font, fit(name, x + w - 3 - textX), textX, rowY + (PICK_H - 8) / 2, 0xFFFFFFFF, false);
        }
    }

    // the picker row under the mouse (the last one is "None"), or -1
    private int pickAt(double mouseX, double mouseY) {
        if (!pickerOpen) return -1;
        int y = pickY() + (pickRows().isEmpty() ? 12 : 0) + 1;
        if (mouseX < panelX || mouseX >= panelX + panelW || mouseY < y || mouseY >= y + pickRowsShown() * PICK_H) return -1;
        int index = pickScroll + (int) ((mouseY - y) / PICK_H);
        return index < pickRowCount() ? index : -1;
    }

    private boolean overPicker(double mouseX, double mouseY) {
        return pickerOpen && mouseX >= panelX && mouseX < panelX + panelW && mouseY >= pickY()
                && mouseY < pickY() + (pickRows().isEmpty() ? 12 : 0) + pickRowsShown() * PICK_H + 2;
    }

    private boolean onPickerButton(double mouseX, double mouseY) {
        return mouseX >= pickX && mouseX < pickX + pickW && mouseY >= 3 && mouseY < 3 + BAR_H;
    }

    private boolean overPanel(double mouseX, double mouseY) {
        return overResults(mouseX, mouseY) || overPicker(mouseX, mouseY);
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
    // Search, picker and hold
    // =====================================================================

    // the search box's responder: runs at every typed or deleted letter
    private void searchChanged(String text) {
        results = parent.searchFor(text);
        resultSelected = 0;
        resultScroll = 0;
    }

    // plays a result (the iPod screen behind this one does it, so the iPod is left on that list), then empties
    // the box and takes the keyboard focus away from it, so Space and the arrows control the music again
    private void playResult(int index) {
        if (index < 0 || index >= results.size()) return;
        parent.playSearchResult(results.get(index));
        assert search != null;
        search.setValue(""); // the responder empties the results
        setFocused(null);
    }

    private void moveResult(int step) {
        if (results.isEmpty()) return;
        resultSelected = Mth.clamp(resultSelected + step, 0, results.size() - 1);
        if (resultSelected < resultScroll) resultScroll = resultSelected;
        if (resultSelected >= resultScroll + RESULT_ROWS) resultScroll = resultSelected - RESULT_ROWS + 1;
    }

    private void togglePicker() {
        pickerOpen = !pickerOpen;
        pickScroll = 0;
        if (pickerOpen) Wallpaper.refresh(); // new or removed pictures show up
    }

    private void pick(int index) {
        List<Path> pictures = pickRows();
        IpodSettings.chooseWallpaper(index < pictures.size() ? pictures.get(index).getFileName().toString() : null);
    }

    private void startHold(Hold which) {
        hold = which;
        holdStart = Util.getMillis();
        holdLastStep = holdStart;
        holdSkipped = false;
    }

    // the button or key was let go: a quick press changes track, after a hold that skipped nothing more happens
    private void endHold() {
        if (!holdSkipped) {
            if (hold == Hold.PREVIOUS) IpodPlayer.previous();
            else if (hold == Hold.NEXT) IpodPlayer.next();
        }
        hold = Hold.NONE;
    }

    @Override
    public void tick() {
        // previous or next held down: skip back or ahead 10 seconds every 400 ms, after the first 400 ms
        if (hold != Hold.NONE && IpodPlayer.canSkipSeconds()) {
            long now = Util.getMillis();
            if (now - holdStart >= HOLD_START_MS && now - holdLastStep >= HOLD_STEP_MS) {
                holdLastStep = now;
                holdSkipped = true;
                IpodPlayer.skipSeconds(hold == Hold.NEXT ? HOLD_SKIP_SECONDS : -HOLD_SKIP_SECONDS);
            }
        }
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
        assert search != null;

        // 1) the open panels and the picker button come first: they lie on top of everything else
        if (onPickerButton(mouseX, mouseY)) {
            togglePicker();
            return true;
        }
        if (pickerOpen) {
            if (overPicker(mouseX, mouseY)) {
                int index = pickAt(mouseX, mouseY);
                if (index >= 0) pick(index);
                return true;
            }
            pickerOpen = false; // a click anywhere else closes the picker and then does what it does
        }
        if (overResults(mouseX, mouseY)) {
            playResult(resultAt(mouseX, mouseY));
            return true;
        }

        // 2) the search box: a click into it gives it the keyboard, a click anywhere else takes it away
        if (search.isMouseOver(mouseX, mouseY)) return super.mouseClicked(mouseX, mouseY, button);
        setFocused(null);

        // 3) the rest of the top strip: a double-click goes back, like the one that opened this view
        if (mouseY < TOP_STRIP) {
            long now = Util.getMillis();
            if (lastTopClick >= 0 && now - lastTopClick <= DOUBLE_CLICK_MS) {
                onClose();
            } else {
                lastTopClick = now;
            }
            return true;
        }

        // 4) the buttons (only while something is loaded, as only then are they drawn)
        if (IpodPlayer.hasTrack()) {
            if (onButton(shuffleX, mouseX, mouseY)) {
                IpodSettings.toggleShuffle();
                return true;
            }
            if (onButton(prevX, mouseX, mouseY)) {
                startHold(Hold.PREVIOUS); // decided when the button is let go (mouseReleased)
                return true;
            }
            if (onButton(playX, mouseX, mouseY)) {
                IpodPlayer.togglePause();
                return true;
            }
            if (onButton(nextX, mouseX, mouseY)) {
                startHold(Hold.NEXT);
                return true;
            }
            if (onButton(repeatX, mouseX, mouseY)) {
                IpodSettings.cycleRepeat();
                return true;
            }
        }

        // 5) a click on the volume bar (or a little above or below it) sets the volume there
        if (mouseX >= volumeX && mouseX <= volumeX + volumeW && mouseY >= volumeY - 4 && mouseY <= volumeY + 7) {
            IpodVolume.set((float) ((mouseX - volumeX) / volumeW));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && hold != Hold.NONE) {
            endHold();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : 1; // the wheel turned up moves a list up
        if (overResults(mouseX, mouseY)) {
            resultScroll = Mth.clamp(resultScroll + step, 0, Math.max(0, results.size() - RESULT_ROWS));
            return true;
        }
        if (overPicker(mouseX, mouseY)) {
            pickScroll = Mth.clamp(pickScroll + step, 0, Math.max(0, pickRowCount() - PICK_ROWS));
            return true;
        }
        IpodVolume.set(IpodVolume.get() + (float) scrollY * 0.05F); // anywhere else: the volume, like over the wheel
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pickerOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            pickerOpen = false;
            return true;
        }
        if (search != null && search.isFocused()) {
            // while you type, the keys belong to the search box and its results, not to the music
            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE -> { // empty the box and leave it; a second Esc goes back to the iPod
                    search.setValue("");
                    setFocused(null);
                }
                case GLFW.GLFW_KEY_UP -> moveResult(-1);
                case GLFW.GLFW_KEY_DOWN -> moveResult(1);
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> playResult(resultSelected);
                default -> {
                    return super.keyPressed(keyCode, scanCode, modifiers); // the box gets the key (letters, Backspace...)
                }
            }
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> IpodPlayer.togglePause();
            // a key held down sends keyPressed again and again ("repeat"): only the first press starts the hold
            case GLFW.GLFW_KEY_LEFT -> {
                if (hold == Hold.NONE) startHold(Hold.PREVIOUS);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (hold == Hold.NONE) startHold(Hold.NEXT);
            }
            case GLFW.GLFW_KEY_UP -> IpodVolume.set(IpodVolume.get() + 0.05F);
            case GLFW.GLFW_KEY_DOWN -> IpodVolume.set(IpodVolume.get() - 0.05F);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers); // Esc ends up in onClose()
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_LEFT && hold == Hold.PREVIOUS) || (keyCode == GLFW.GLFW_KEY_RIGHT && hold == Hold.NEXT)) {
            endHold();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
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
