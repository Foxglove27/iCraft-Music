package net.foxglove.icraft.client.screen;

import net.foxglove.icraft.client.IpodPlayer;
import net.foxglove.icraft.client.IpodVolume;
import net.foxglove.icraft.client.audio.CoverArt;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IpodScreen extends Screen {
    private static final int BUTTON_W = 180;
    private static final int BUTTON_H = 20;
    private static final int ROW_H = 22;
    private static final int ALBUM_ROW_H = 36;
    private static final int COVER_SMALL = 32;
    private static final int COVER_LARGE = 48;
    private static final int HEADER_H = COVER_LARGE + 6;
    private static final int FOOTER_H = 4 + ROW_H + BUTTON_H; // controls row + page row
    private static final int TOP_MARGIN = 30;
    private static final int BOTTOM_MARGIN = 20;

    // widths of the controls row: [Pause/Play] [Stop] [volume slider]
    private static final int PLAY_W = 52;
    private static final int STOP_W = 40;
    private static final int GAP = 4;

    private record Track(Component name, Runnable play) {}
    private record AlbumView(Component name, @Nullable Path cover, @Nullable ItemStack icon, List<Track> tracks) {}
    private record CoverSlot(AlbumView album, int x, int y, int size) {}

    // A slider that edits IpodVolume
    private static class VolumeSlider extends AbstractSliderButton {
        public VolumeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), IpodVolume.get());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.icraft.ipod.volume", (int) Math.round(value * 100)));
        }

        @Override
        protected void applyValue() {
            IpodVolume.set((float) value);
        }
    }

    private final List<AlbumView> albums = new ArrayList<>();
    private final List<CoverSlot> coverSlots = new ArrayList<>();
    @Nullable
    private AlbumView open; // null = showing the album list
    @Nullable
    private Button pauseButton;
    private int page;
    private int pageCount = 1;
    private int listTop = TOP_MARGIN;
    private int navY;

    public IpodScreen(List<ItemStack> discs, List<LocalMusic.Album> library, @Nullable BlockPos speaker) {
        super(Component.translatable("screen.icraft.ipod"));

        if (!discs.isEmpty()) {
            List<Track> tracks = new ArrayList<>();
            for (ItemStack disc : discs) {
                tracks.add(new Track(discName(disc), () -> IpodPlayer.play(disc, speaker)));
            }
            albums.add(new AlbumView(Component.translatable("screen.icraft.ipod.discs"),
                    null, new ItemStack(Items.MUSIC_DISC_CAT), tracks));
        }

        for (LocalMusic.Album album : library) {
            List<Track> tracks = new ArrayList<>();
            for (Path file : album.files()) {
                tracks.add(new Track(Component.literal(LocalMusic.displayName(file)),
                        () -> IpodPlayer.playFile(file, speaker)));
            }
            albums.add(new AlbumView(Component.literal(album.name()), album.cover(), null, tracks));
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
        coverSlots.clear();
        int cx = this.width / 2;
        int left = cx - BUTTON_W / 2;
        boolean tracksView = open != null;

        int rowH = tracksView ? ROW_H : ALBUM_ROW_H;
        int headerH = tracksView ? HEADER_H : 0;
        int itemCount = tracksView ? open.tracks().size() : albums.size();

        // fit as many rows as the window allows
        int available = this.height - TOP_MARGIN - BOTTOM_MARGIN - headerH - FOOTER_H;
        int perPage = Math.max(1, Math.min(8, available / rowH));
        pageCount = Math.max(1, (itemCount + perPage - 1) / perPage);
        page = Math.min(page, pageCount - 1);

        int rows = Math.max(1, Math.min(perPage, itemCount));
        int blockHeight = headerH + rows * rowH + 4 + BUTTON_H + (pageCount > 1 ? ROW_H : 0);
        listTop = Math.max(TOP_MARGIN, (this.height - blockHeight) / 2);

        int y = listTop + headerH;
        int start = page * perPage;
        int end = Math.min(start + perPage, itemCount);

        if (tracksView) {
            coverSlots.add(new CoverSlot(open, left, listTop, COVER_LARGE));
            for (int i = start; i < end; i++) {
                Track track = open.tracks().get(i);
                addRenderableWidget(Button.builder(track.name(), b -> track.play().run())
                        .bounds(left, y, BUTTON_W, BUTTON_H).build());
                y += rowH;
            }
            addRenderableWidget(Button.builder(Component.translatable("screen.icraft.ipod.back"), b -> {
                open = null;
                page = 0;
                rebuildWidgets();
            }).bounds(8, 8, 70, BUTTON_H).build());
        } else {
            for (int i = start; i < end; i++) {
                AlbumView album = albums.get(i);
                coverSlots.add(new CoverSlot(album, left, y + 2, COVER_SMALL));
                Component label = album.name().copy()
                        .append(Component.literal(" (" + album.tracks().size() + ")"));
                addRenderableWidget(Button.builder(label, b -> {
                    open = album;
                    page = 0;
                    rebuildWidgets();
                }).bounds(left + COVER_SMALL + 6, y + 8, BUTTON_W - COVER_SMALL - 6, BUTTON_H).build());
                y += rowH;
            }
        }

        // controls row: [Pause/Play] [Stop] [volume slider]
        int stopY = listTop + headerH + rows * rowH + 4;
        pauseButton = Button.builder(Component.translatable("screen.icraft.ipod.pause"),
                        b -> IpodPlayer.togglePause())
                .bounds(left, stopY, PLAY_W, BUTTON_H).build();
        addRenderableWidget(pauseButton);

        addRenderableWidget(Button.builder(Component.translatable("screen.icraft.ipod.stop"),
                        b -> IpodPlayer.stop())
                .bounds(left + PLAY_W + GAP, stopY, STOP_W, BUTTON_H).build());

        int sliderX = left + PLAY_W + STOP_W + 2 * GAP;
        addRenderableWidget(new VolumeSlider(sliderX, stopY, BUTTON_W - (sliderX - left), BUTTON_H));

        updatePauseButton();

        if (pageCount > 1) {
            navY = stopY + ROW_H;
            Button prev = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
                    .bounds(left, navY, 40, BUTTON_H).build();
            Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                    .bounds(left + BUTTON_W - 40, navY, 40, BUTTON_H).build();
            prev.active = page > 0;
            next.active = page < pageCount - 1;
            addRenderableWidget(prev);
            addRenderableWidget(next);
        }
    }

    // Runs every tick while the screen is open: keeps the Pause/Play button up to date
    @Override
    public void tick() {
        updatePauseButton();
    }

    private void updatePauseButton() {
        if (pauseButton == null) return;
        pauseButton.setMessage(Component.translatable(
                IpodPlayer.isPaused() ? "screen.icraft.ipod.play" : "screen.icraft.ipod.pause"));
        pauseButton.active = IpodPlayer.canTogglePause(); // greyed out while nothing plays or during the cooldown
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, listTop - 20, 0xFFFFFF);

        for (CoverSlot slot : coverSlots) {
            drawCover(graphics, slot);
        }

        if (open != null) {
            int textX = this.width / 2 - BUTTON_W / 2 + COVER_LARGE + 8;
            graphics.drawString(this.font, open.name(), textX, listTop + 8, 0xFFFFFF);
            graphics.drawString(this.font,
                    Component.translatable("screen.icraft.ipod.tracks", open.tracks().size()),
                    textX, listTop + 22, 0xAAAAAA);
        }

        if (albums.isEmpty()) {
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

    private void drawCover(GuiGraphics g, CoverSlot slot) {
        int x = slot.x();
        int y = slot.y();
        int s = slot.size();
        AlbumView album = slot.album();

        ResourceLocation texture = album.cover() != null ? CoverArt.get(album.cover()) : null;
        if (texture != null) {
            g.blit(texture, x, y, s, s, 0, 0, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE);
            return;
        }

        g.fill(x, y, x + s, y + s, 0xFF3A3A3A);
        if (album.icon() != null) {
            float scale = s / 16.0F;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(scale, scale, 1.0F);
            g.renderItem(album.icon(), 0, 0);
            g.pose().popPose();
        } else {
            String name = album.name().getString();
            String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            g.drawCenteredString(this.font, initial, x + s / 2, y + s / 2 - 4, 0xAAAAAA);
        }
    }

    @Override
    public void removed() {
        IpodVolume.save(); // write the volume to the config file when the screen closes
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}