package net.foxglove.icraft.client.screen;

import net.foxglove.icraft.client.screen.manual.ManualBlock;
import net.foxglove.icraft.client.screen.manual.ManualContent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The iCraft Manual: the chapters on the left (the open chapter also lists its pages), the page on the right
 * (scrolls with the mouse wheel), and page buttons at the bottom. Opened from the manual item or from the
 * iPod's Manual menu row; Esc goes back to the screen it came from. The content is in {@link ManualContent}.
 */
public class ManualScreen extends Screen {
    private static final int MAX_W = 320, MAX_H = 200;
    private static final int SIDEBAR_MIN = 90, SIDEBAR_MAX = 130;
    private static final int HEADER_H = 20, FOOTER_H = 18, PAD = 8;
    private static final int CHAPTER_ROW = 13, PAGE_ROW = 11, BLOCK_GAP = 6;

    // colours in the iPod's style: white and light grey, a blue highlight
    private static final int BORDER = 0xFF7D7D7D, PANEL = 0xFFF7F7F7, SIDEBAR = 0xFFE4E4E4, HEADER = 0xFFCFE3F5;
    private static final int HIGHLIGHT = 0xFF3C82D8, DARK = 0xFF202020, GREY = 0xFF707070, DISABLED = 0xFFB0B0B0;

    // where the reader was; kept while the game runs, so reopening continues there
    private static int lastPage;

    @Nullable
    private final Screen parent;
    private final List<ManualContent.Page> pages = new ArrayList<>(); // all pages of all chapters, in order
    private int pageIndex;
    private double scroll;

    // the panel, worked out in init() from the window size
    private int left, top, panelW, panelH;
    private int sidebarW; // as wide as the longest chapter or page title needs, within limits

    public ManualScreen(@Nullable Screen parent) {
        super(Component.translatable("item.icraft_music.manual"));
        this.parent = parent;
        for (ManualContent.Chapter chapter : ManualContent.chapters()) pages.addAll(chapter.pages());
        pageIndex = Mth.clamp(lastPage, 0, pages.size() - 1);
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_W, this.width - 16);
        panelH = Math.min(MAX_H, this.height - 16);
        left = (this.width - panelW) / 2;
        top = (this.height - panelH) / 2;
        int widest = this.font.width(this.title.copy().withStyle(ChatFormatting.BOLD)) + 12;
        for (ManualContent.Chapter chapter : ManualContent.chapters()) {
            widest = Math.max(widest, this.font.width(chapter.title().copy().withStyle(ChatFormatting.BOLD)) + 12);
            for (ManualContent.Page page : chapter.pages()) widest = Math.max(widest, this.font.width(page.title()) + 18);
        }
        sidebarW = Mth.clamp(widest, SIDEBAR_MIN, Math.min(SIDEBAR_MAX, panelW * 2 / 5));
    }

    // ---- layout ----

    private int contentX() {
        return left + sidebarW + PAD;
    }

    private int contentW() {
        return panelW - sidebarW - 2 * PAD;
    }

    private int bodyTop() {
        return top + HEADER_H + 4;
    }

    private int bodyBottom() {
        return top + panelH - FOOTER_H - 2;
    }

    private ManualContent.Page page() {
        return pages.get(pageIndex);
    }

    private int bodyHeight() {
        int h = 0;
        for (ManualBlock block : page().blocks()) {
            int bh = block.height(this.font, contentW());
            if (bh > 0) h += bh + BLOCK_GAP;
        }
        return Math.max(0, h - BLOCK_GAP);
    }

    private double maxScroll() {
        return Math.max(0, bodyHeight() - (bodyBottom() - bodyTop()));
    }

    private void goToPage(int index) {
        pageIndex = Mth.clamp(index, 0, pages.size() - 1);
        lastPage = pageIndex;
        scroll = 0;
    }

    // ---- drawing ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // the dimmed background

        // panel, sidebar, header strip
        g.fill(left - 1, top - 1, left + panelW + 1, top + panelH + 1, BORDER);
        g.fill(left, top, left + panelW, top + panelH, PANEL);
        g.fill(left, top, left + sidebarW, top + panelH, SIDEBAR);
        g.fill(left + sidebarW, top, left + sidebarW + 1, top + panelH, BORDER);
        g.fill(left + sidebarW + 1, top, left + panelW, top + HEADER_H, HEADER);

        drawSidebar(g, mouseX, mouseY);

        // page title
        Component title = page().title().copy().withStyle(ChatFormatting.BOLD);
        g.drawString(this.font, fit(title, contentW()), contentX(), top + (HEADER_H - 8) / 2, DARK, false);

        // the page itself, clipped to the body and scrolled
        ManualBlock.Hover hover = new ManualBlock.Hover();
        boolean mouseInBody = mouseX >= contentX() && mouseX < contentX() + contentW()
                && mouseY >= bodyTop() && mouseY < bodyBottom();
        g.enableScissor(contentX() - 1, bodyTop(), contentX() + contentW() + 1, bodyBottom());
        int y = bodyTop() - (int) scroll;
        for (ManualBlock block : page().blocks()) {
            int h = block.height(this.font, contentW());
            if (h <= 0) continue;
            block.render(g, this.font, contentX(), y, contentW(),
                    mouseInBody ? mouseX : -1, mouseInBody ? mouseY : -1, hover);
            y += h + BLOCK_GAP;
        }
        g.disableScissor();

        // a thin scroll bar when the page is longer than the body
        double max = maxScroll();
        if (max > 0) {
            int trackTop = bodyTop(), trackH = bodyBottom() - bodyTop();
            int barH = Math.max(12, trackH * trackH / (trackH + (int) max));
            int barY = trackTop + (int) ((trackH - barH) * (scroll / max));
            int barX = left + panelW - 4;
            g.fill(barX, trackTop, barX + 2, trackTop + trackH, SIDEBAR);
            g.fill(barX, barY, barX + 2, barY + barH, GREY);
        }

        drawFooter(g, mouseX, mouseY);

        if (hover.stack != null) g.renderTooltip(this.font, hover.stack, mouseX, mouseY);
    }

    // The chapters; the open one also lists its pages (indented). Rows are clickable.
    private void drawSidebar(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, fit(this.title.copy().withStyle(ChatFormatting.BOLD), sidebarW - 2 * 6),
                left + 6, top + (HEADER_H - 8) / 2, DARK, false);
        for (SidebarRow row : sidebarRows()) {
            boolean current = row.isPage() ? row.pageIndex() == pageIndex : row.chapter() == chapterOf(pageIndex);
            boolean hovered = mouseX >= left && mouseX < left + sidebarW && mouseY >= row.y() && mouseY < row.y() + row.height();
            int x = left + (row.isPage() ? 12 : 6);
            int color;
            if (current && row.isPage()) {
                g.fill(left + 3, row.y(), left + sidebarW - 3, row.y() + row.height(), HIGHLIGHT);
                color = 0xFFFFFFFF;
            } else {
                color = row.isPage() ? GREY : DARK;
                if (hovered) g.fill(left + 3, row.y(), left + sidebarW - 3, row.y() + row.height(), 0x22000000);
            }
            Component label = row.isPage() ? row.label() : row.label().copy().withStyle(current ? ChatFormatting.BOLD : ChatFormatting.RESET);
            g.drawString(this.font, fit(label, left + sidebarW - 4 - x), x, row.y() + (row.height() - 8) / 2, color, false);
        }
    }

    private record SidebarRow(int chapter, int pageIndex, boolean isPage, Component label, int y, int height) {}

    private List<SidebarRow> sidebarRows() {
        List<SidebarRow> rows = new ArrayList<>();
        int y = top + HEADER_H + 4;
        int openChapter = chapterOf(pageIndex);
        int first = 0; // index of the chapter's first page in 'pages'
        List<ManualContent.Chapter> chapters = ManualContent.chapters();
        for (int c = 0; c < chapters.size(); c++) {
            ManualContent.Chapter chapter = chapters.get(c);
            rows.add(new SidebarRow(c, first, false, chapter.title(), y, CHAPTER_ROW));
            y += CHAPTER_ROW;
            if (c == openChapter) {
                for (int p = 0; p < chapter.pages().size(); p++) {
                    rows.add(new SidebarRow(c, first + p, true, chapter.pages().get(p).title(), y, PAGE_ROW));
                    y += PAGE_ROW;
                }
            }
            first += chapter.pages().size();
        }
        return rows;
    }

    private int chapterOf(int index) {
        int first = 0;
        List<ManualContent.Chapter> chapters = ManualContent.chapters();
        for (int c = 0; c < chapters.size(); c++) {
            first += chapters.get(c).pages().size();
            if (index < first) return c;
        }
        return chapters.size() - 1;
    }

    // "<   3 / 17   >" at the bottom of the page side
    private void drawFooter(GuiGraphics g, int mouseX, int mouseY) {
        int y = top + panelH - FOOTER_H;
        g.fill(left + sidebarW + 1, y - 1, left + panelW, y, SIDEBAR);
        String number = (pageIndex + 1) + " / " + pages.size();
        int mid = contentX() + contentW() / 2;
        g.drawString(this.font, number, mid - this.font.width(number) / 2, y + 5, GREY, false);
        drawButton(g, prevButtonX(), y + 2, "<", pageIndex > 0, mouseX, mouseY);
        drawButton(g, nextButtonX(), y + 2, ">", pageIndex < pages.size() - 1, mouseX, mouseY);
    }

    private static final int BUTTON_W = 20, BUTTON_H = 14;

    private int prevButtonX() {
        return contentX();
    }

    private int nextButtonX() {
        return contentX() + contentW() - BUTTON_W;
    }

    private void drawButton(GuiGraphics g, int x, int y, String label, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
        g.fill(x, y, x + BUTTON_W, y + BUTTON_H, enabled ? (hovered ? HIGHLIGHT : BORDER) : SIDEBAR);
        g.drawString(this.font, label, x + (BUTTON_W - this.font.width(label)) / 2, y + 3,
                enabled ? 0xFFFFFFFF : DISABLED, false);
    }

    // shortens a text with "..." so it fits
    private net.minecraft.util.FormattedCharSequence fit(Component text, int width) {
        if (this.font.width(text) <= width) return text.getVisualOrderText();
        String ellipsis = "...";
        return net.minecraft.locale.Language.getInstance().getVisualOrder(net.minecraft.network.chat.FormattedText.composite(
                this.font.substrByWidth(text, Math.max(0, width - this.font.width(ellipsis))),
                net.minecraft.network.chat.FormattedText.of(ellipsis)));
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (SidebarRow row : sidebarRows()) {
                if (mouseX >= left && mouseX < left + sidebarW && mouseY >= row.y() && mouseY < row.y() + row.height()) {
                    goToPage(row.pageIndex()); // a chapter opens on its first page
                    return true;
                }
            }
            int y = top + panelH - FOOTER_H + 2;
            if (mouseY >= y && mouseY < y + BUTTON_H) {
                if (mouseX >= prevButtonX() && mouseX < prevButtonX() + BUTTON_W && pageIndex > 0) {
                    goToPage(pageIndex - 1);
                    return true;
                }
                if (mouseX >= nextButtonX() && mouseX < nextButtonX() + BUTTON_W && pageIndex < pages.size() - 1) {
                    goToPage(pageIndex + 1);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - scrollY * 12, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> {
                if (pageIndex > 0) goToPage(pageIndex - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_PAGE_DOWN -> {
                if (pageIndex < pages.size() - 1) goToPage(pageIndex + 1);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                scroll = Mth.clamp(scroll - 12, 0, maxScroll());
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                scroll = Mth.clamp(scroll + 12, 0, maxScroll());
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    // Esc: back to the iPod if it was opened from there, otherwise into the game
    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
