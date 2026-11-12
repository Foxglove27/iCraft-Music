package net.foxglove.icraft.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class IpodScreen extends Screen {
    public IpodScreen() {
        super(Component.translatable("screen.icraft.ipod"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        addRenderableWidget(Button.builder(Component.literal("Play / Pause"), b -> {
            // TODO: toggle playback
        }).bounds(cx - 50, cy - 10, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Next"), b -> {
            // TODO: next track
        }).bounds(cx - 50, cy + 14, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick); // background + buttons
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // keep the game running so music doesn't freeze in singleplayer
    }
}