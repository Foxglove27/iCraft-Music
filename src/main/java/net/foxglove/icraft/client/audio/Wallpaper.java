package net.foxglove.icraft.client.audio;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.foxglove.icraft.iCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The player's own picture behind the iPod's display (B22): {@code <game folder>/ipod/wallpaper.png} (or .jpg/.jpeg).
 * Like {@link CoverArt} the file is decoded on a background thread and only turned into a texture on the render
 * thread, but the picture keeps its shape (a cover is squashed into a square), so it can be cropped to fill any
 * rectangle. There is only ever one wallpaper texture.
 */
public final class Wallpaper {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** A bigger picture is scaled down to this many pixels on its longer side (video memory). */
    private static final int MAX_SIZE = 1024;
    private static final String[] NAMES = {"wallpaper.png", "wallpaper.jpg", "wallpaper.jpeg"};
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "wallpaper");

    // What the background thread hands to the render thread. 'image' is null when the file could not be read.
    private record Decoded(Path file, long modified, @Nullable NativeImage image) {}

    private static final AtomicReference<Decoded> READY = new AtomicReference<>();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "iCraft wallpaper decoder");
        t.setDaemon(true); // never keeps the game from closing
        return t;
    });

    // ---- render thread only ----
    @Nullable
    private static Path file;      // the file the texture (or the failed attempt) belongs to
    private static long modified;  // its last-modified time then, so a changed picture is read again
    private static int width;
    private static int height;
    private static boolean registered;
    private static boolean loading;

    private Wallpaper() {}

    /**
     * Looks for the picture and starts reading it if it is new or was changed. Cheap when nothing changed.
     * Called when the iPod opens and when the Wallpaper setting is switched on. Render thread.
     */
    public static void refresh() {
        if (loading) return;
        Path found = find();
        if (found == null) { // the picture was removed
            release();
            file = null;
            modified = 0;
            return;
        }
        long changed = lastModified(found);
        if (found.equals(file) && changed == modified) return; // already loaded (or already failed)
        loading = true;
        DECODER.execute(() -> READY.set(decode(found, changed)));
    }

    /**
     * Draws the picture so it fills the rectangle (the middle is kept, nothing is stretched) and darkens it with
     * {@code darken} (0 = not at all, 255 = black), so white text on it stays readable. Render thread.
     *
     * @return false if there is no picture (nothing was drawn)
     */
    public static boolean draw(GuiGraphics g, int x, int y, int w, int h, int darken) {
        Decoded ready = READY.getAndSet(null);
        if (ready != null) register(ready);
        if (!registered || w <= 0 || h <= 0) return false;

        // "Cover" scaling: the scale at which the picture covers the whole rectangle in both directions.
        // Of the scaled picture only a w x h window in the middle is shown, which is (w / scale) x (h / scale)
        // pixels of the original.
        float scale = Math.max(w / (float) width, h / (float) height);
        int srcW = Math.round(w / scale);
        int srcH = Math.round(h / scale);
        float u = (width - srcW) / 2.0F;
        float v = (height - srcH) / 2.0F;
        g.blit(TEXTURE, x, y, w, h, u, v, srcW, srcH, width, height);
        g.fill(x, y, x + w, y + h, (darken & 0xFF) << 24);
        return true;
    }

    /** Releases the texture (leaving a world). Render thread. */
    public static void clear() {
        release();
        file = null;
        modified = 0;
    }

    @Nullable
    private static Path find() {
        for (String name : NAMES) {
            Path candidate = LocalMusic.libraryRoot().resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // background thread: read the file and scale it down (keeping its shape) if it is bigger than MAX_SIZE
    private static Decoded decode(Path path, long changed) {
        try {
            BufferedImage source = ImageIO.read(path.toFile());
            if (source == null) throw new IOException("Unsupported image format");
            float shrink = Math.min(1.0F, MAX_SIZE / (float) Math.max(source.getWidth(), source.getHeight()));
            int w = Math.max(1, Math.round(source.getWidth() * shrink));
            int h = Math.max(1, Math.round(source.getHeight() * shrink));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(source, 0, 0, w, h, null);
            g2.dispose();
            // NativeImage (Minecraft's own image class) reads PNG, so the pixels go through a PNG in memory
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", png);
            return new Decoded(path, changed, NativeImage.read(new ByteArrayInputStream(png.toByteArray())));
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not load the iPod wallpaper {}", path, e); // once per file version, see refresh()
            return new Decoded(path, changed, null);
        }
    }

    // render thread: turn the decoded pixels into the texture
    private static void register(Decoded ready) {
        loading = false;
        release();
        file = ready.file();
        modified = ready.modified();
        NativeImage image = ready.image();
        if (image == null) return; // remembered as failed until the file changes
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false); // smooth when drawn smaller than it is
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        width = image.getWidth();
        height = image.getHeight();
        registered = true;
        LOGGER.info("iPod wallpaper loaded: {} ({} x {})", Objects.requireNonNull(file).getFileName(), width, height);
    }

    private static void release() {
        if (!registered) return;
        Minecraft.getInstance().getTextureManager().release(TEXTURE);
        registered = false;
    }
}
