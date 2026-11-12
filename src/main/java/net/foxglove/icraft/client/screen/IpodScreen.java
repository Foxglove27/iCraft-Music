package net.foxglove.icraft.client.screen;

import net.foxglove.icraft.client.IpodPlayer;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IpodScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int BUTTON_W = 180;
    private static final int BUTTON_H = 20;
    private static final int PAGE_SIZE = 6;

    private record Track(Component name, Runnable play) {}

    private final List<Track> tracks = new ArrayList<>();
    private int page;
    private int pageCount = 1;
    private int listTop;
    private int navY;

    public IpodScreen(List<ItemStack> discs, List<Path> files, @Nullable BlockPos speaker) {
        super(Component.translatable("screen.icraft.ipod"));
        for (ItemStack disc : discs) {
            tracks.add(new Track(discName(disc), () -> IpodPlayer.play(disc, speaker)));
        }
        for (Path file : files) {
            tracks.add(new Track(Component.literal(LocalMusic.displayName(file)),
                    () -> IpodPlayer.playFile(file, speaker)));
        }
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
        pageCount = Math.max(1, (tracks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pageCount - 1);

        int rows = Math.max(1, Math.min(PAGE_SIZE, tracks.size()));
        int blockHeight = rows * ROW_H + 4 + BUTTON_H + (pageCount > 1 ? ROW_H : 0);
        listTop = Math.max(40, (this.height - blockHeight) / 2);

        int y = listTop;
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, tracks.size());
        for (int i = start; i < end; i++) {
            Track track = tracks.get(i);
            addRenderableWidget(Button.builder(track.name(), b -> track.play().run())
                    .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
            y += ROW_H;
        }

        int stopY = listTop + rows * ROW_H + 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.icraft.ipod.stop"),
                        b -> IpodPlayer.stop())
                .bounds(cx - BUTTON_W / 2, stopY, BUTTON_W, BUTTON_H).build());

        if (pageCount > 1) {
            navY = stopY + ROW_H;
            Button prev = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
                    .bounds(cx - BUTTON_W / 2, navY, 40, BUTTON_H).build();
            Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                    .bounds(cx + BUTTON_W / 2 - 40, navY, 40, BUTTON_H).build();
            prev.active = page > 0;
            next.active = page < pageCount - 1;
            addRenderableWidget(prev);
            addRenderableWidget(next);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, listTop - 20, 0xFFFFFF);
        if (tracks.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.icraft.ipod.empty"),
                    this.width / 2, listTop + 6, 0xAAAAAA);
        }
        if (pageCount > 1) {
            graphics.drawCenteredString(this.font,
                    Component.literal((page + 1) + " / " + pageCount),
                    this.width / 2, navY + 6, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}