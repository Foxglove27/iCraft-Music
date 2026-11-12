package net.foxglove.icraft.client.screen;

import net.foxglove.icraft.client.IpodPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class IpodScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int BUTTON_W = 180;
    private static final int BUTTON_H = 20;

    private final List<ItemStack> discs;
    @Nullable
    private final BlockPos speaker;
    private int listTop;

    public IpodScreen(List<ItemStack> discs, @Nullable BlockPos speaker) {
        super(Component.translatable("screen.icraft.ipod"));
        this.discs = discs;
        this.speaker = speaker;
    }

    private static Component discName(ItemStack disc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return JukeboxSong.fromStack(mc.level.registryAccess(), disc)
                    .map(holder -> holder.value().description())
                    .orElse(disc.getHoverName());
        }
        return disc.getHoverName();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int listRows = Math.max(discs.size(), 1); // keep one row free for the empty message
        int blockHeight = listRows * ROW_H + ROW_H + 4; // disc rows + stop button
        listTop = Math.max(40, (this.height - blockHeight) / 2);

        int y = listTop;
        for (ItemStack disc : discs) {
            addRenderableWidget(Button.builder(discName(disc), b -> IpodPlayer.play(disc, speaker))
                    .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
            y += ROW_H;
        }

        int stopY = listTop + listRows * ROW_H + 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.icraft.ipod.stop"),
                        b -> IpodPlayer.stop())
                .bounds(cx - BUTTON_W / 2, stopY, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, listTop - 20, 0xFFFFFF);
        if (discs.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.icraft.ipod.empty"),
                    this.width / 2, listTop + 6, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}