package net.foxglove.icraft.client.screen.manual;

import net.foxglove.icraft.iCraft;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * One piece of a manual page: a paragraph, a row of items, a crafting recipe or a small picture. The screen stacks
 * the blocks of a page from top to bottom; each block says how tall it is for a given width and draws itself.
 */
public sealed interface ManualBlock {
    int TEXT_COLOR = 0xFF303030;
    int SLOT = 18; // a slot like in an inventory: 16 px item + 1 px border on each side

    int height(Font font, int width);

    void render(GuiGraphics g, Font font, int x, int y, int width, int mouseX, int mouseY, Hover hover);

    /** Collects the item under the mouse while a page is drawn, so the screen can show its tooltip on top. */
    final class Hover {
        @Nullable
        public ItemStack stack;
    }

    // ---- helpers shared by the blocks ----

    private static List<FormattedCharSequence> lines(Font font, Component text, int width) {
        return font.split(text, Math.max(20, width));
    }

    /** Draws an item in a slot (grey frame like the inventory) and records it when the mouse is over it. */
    private static void slot(GuiGraphics g, int x, int y, ItemStack stack, int mouseX, int mouseY, Hover hover) {
        g.fill(x, y, x + SLOT, y + SLOT, 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFFC6C6C6);
        if (stack.isEmpty()) return;
        g.renderItem(stack, x + 1, y + 1);
        if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) hover.stack = stack;
    }

    /** One of the items an ingredient accepts; several are shown in turn, one per second. */
    private static ItemStack pick(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) return ItemStack.EMPTY;
        return options[(int) ((Util.getMillis() / 1000) % options.length)];
    }

    // ---- the blocks ----

    /** A paragraph from a lang key; {@code args} fill in %s (for example the keys a player has bound). */
    record Text(String key, Supplier<Object[]> args) implements ManualBlock {
        Text(String key) {
            this(key, () -> new Object[0]);
        }

        private Component text() {
            return Component.translatable(key, args.get());
        }

        @Override
        public int height(Font font, int width) {
            return lines(font, text(), width).size() * (font.lineHeight + 1);
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int width, int mouseX, int mouseY, Hover hover) {
            for (FormattedCharSequence line : lines(font, text(), width)) {
                g.drawString(font, line, x, y, TEXT_COLOR, false);
                y += font.lineHeight + 1;
            }
        }
    }

    /** A row of item icons (names as tooltips). */
    record Items(Supplier<List<ItemStack>> stacks) implements ManualBlock {
        @Override
        public int height(Font font, int width) {
            int perRow = Math.max(1, width / SLOT);
            return ((stacks.get().size() + perRow - 1) / perRow) * SLOT;
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int width, int mouseX, int mouseY, Hover hover) {
            int perRow = Math.max(1, width / SLOT);
            List<ItemStack> list = stacks.get();
            for (int i = 0; i < list.size(); i++) {
                slot(g, x + (i % perRow) * SLOT, y + (i / perRow) * SLOT, list.get(i), mouseX, mouseY, hover);
            }
        }
    }

    /** A crafting recipe, read from the game's own recipe list, so it is always the real one. */
    record Recipe(ResourceLocation id) implements ManualBlock {
        Recipe(String path) {
            this(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, path));
        }

        @Nullable
        private net.minecraft.world.item.crafting.Recipe<?> recipe() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return null;
            return mc.level.getRecipeManager().byKey(id).map(holder -> (net.minecraft.world.item.crafting.Recipe<?>) holder.value()).orElse(null);
        }

        @Override
        public int height(Font font, int width) {
            return recipe() == null ? 0 : 3 * SLOT;
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int width, int mouseX, int mouseY, Hover hover) {
            var recipe = recipe();
            if (recipe == null) return;
            List<Ingredient> ingredients = recipe.getIngredients();
            // a shaped recipe keeps its pattern; a shapeless one is laid out row by row
            int w = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int i = row * w + col;
                    ItemStack stack = col < w && i < ingredients.size() ? pick(ingredients.get(i)) : ItemStack.EMPTY;
                    slot(g, x + col * SLOT, y + row * SLOT, stack, mouseX, mouseY, hover);
                }
            }
            // the arrow and the result
            int arrowX = x + 3 * SLOT + 6;
            int midY = y + SLOT + SLOT / 2;
            g.fill(arrowX, midY - 1, arrowX + 14, midY + 1, 0xFF8B8B8B);
            for (int i = 0; i < 4; i++) g.fill(arrowX + 14 + i, midY - 4 + i, arrowX + 15 + i, midY + 4 - i, 0xFF8B8B8B);
            ItemStack result = recipe.getResultItem(Minecraft.getInstance().level.registryAccess());
            slot(g, arrowX + 24, y + SLOT, result, mouseX, mouseY, hover);
        }
    }

    /** A picture next to a few lines of text (the caption is a lang key, one line per \n). */
    abstract sealed class Picture implements ManualBlock permits Wheel, Wall {
        private final String captionKey;
        private final int pictureW, pictureH;

        Picture(String captionKey, int pictureW, int pictureH) {
            this.captionKey = captionKey;
            this.pictureW = pictureW;
            this.pictureH = pictureH;
        }

        abstract void drawPicture(GuiGraphics g, int x, int y, int mouseX, int mouseY, Hover hover);

        private List<FormattedCharSequence> caption(Font font, int width) {
            return lines(font, Component.translatable(captionKey), width - pictureW - 8);
        }

        @Override
        public int height(Font font, int width) {
            return Math.max(pictureH, caption(font, width).size() * (font.lineHeight + 1));
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int width, int mouseX, int mouseY, Hover hover) {
            drawPicture(g, x, y, mouseX, mouseY, hover);
            int textY = y + Math.max(0, (pictureH - caption(font, width).size() * (font.lineHeight + 1)) / 2);
            for (FormattedCharSequence line : caption(font, width)) {
                g.drawString(font, line, x + pictureW + 8, textY, TEXT_COLOR, false);
                textY += font.lineHeight + 1;
            }
        }
    }

    /** The iPod itself (its GUI skin, scaled down) next to what each part of the click wheel does. */
    final class Wheel extends Picture {
        // the iPod inside the 1280 x 1280 skin (see IpodScreen)
        private static final ResourceLocation SKIN = ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "textures/gui/ipod_gui_white.png");
        private static final int U = 288, V = 144, UW = 704, VH = 1024, TEX = 1280;
        private static final int H = 96, W = H * UW / VH;

        Wheel(String captionKey) {
            super(captionKey, W, H);
        }

        @Override
        void drawPicture(GuiGraphics g, int x, int y, int mouseX, int mouseY, Hover hover) {
            g.blit(SKIN, x, y, W, H, U, V, UW, VH, TEX, TEX);
        }
    }

    /** Eight speakers, 2 wide and 4 tall, as a small drawing of the speaker wall. */
    final class Wall extends Picture {
        private final Supplier<ItemStack> speaker;

        Wall(String captionKey, Supplier<ItemStack> speaker) {
            super(captionKey, 2 * 17, 4 * 17);
            this.speaker = speaker;
        }

        @Override
        void drawPicture(GuiGraphics g, int x, int y, int mouseX, int mouseY, Hover hover) {
            ItemStack stack = speaker.get();
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 2; col++) {
                    int px = x + col * 17, py = y + row * 17;
                    g.renderItem(stack, px, py);
                    if (mouseX >= px && mouseX < px + 16 && mouseY >= py && mouseY < py + 16) hover.stack = stack;
                }
            }
        }
    }
}
