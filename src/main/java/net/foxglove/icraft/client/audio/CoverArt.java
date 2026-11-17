package net.foxglove.icraft.client.audio;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.foxglove.icraft.iCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Album art as small textures. Images are decoded and scaled on a background thread (a cover on a network share
 * can be slow); only turning the finished pixels into a texture happens on the render thread.
 */
public class CoverArt {
    public static final int SIZE = 128;
    private static final Logger LOGGER = LogUtils.getLogger();

    private record Ready(Path path, @Nullable NativeImage image) {}

    /** At most this many cover textures stay registered; the least recently shown ones are released (video memory). */
    private static final int MAX_TEXTURES = 128;
    // access order: get() moves a cover to the end, so the eldest entry is the one shown longest ago
    private static final Map<Path, ResourceLocation> LOADED = new LinkedHashMap<>(256, 0.75F, true);
    private static final Set<Path> FAILED = new HashSet<>();
    private static final Set<Path> LOADING = ConcurrentHashMap.newKeySet();
    private static final Queue<Ready> READY = new ConcurrentLinkedQueue<>();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "iCraft cover decoder");
        t.setDaemon(true);
        return t;
    });
    private static int nextId = 0;

    /**
     * The texture of a cover image, or null while it is still loading (ask again next frame) or if it cannot be
     * read. Must be called on the render thread (e.g. from a screen's render method).
     */
    @Nullable
    public static ResourceLocation get(Path imageFile) {
        for (Ready ready; (ready = READY.poll()) != null; ) register(ready);

        ResourceLocation cached = LOADED.get(imageFile);
        if (cached != null) return cached;
        if (FAILED.contains(imageFile)) return null;
        if (LOADING.add(imageFile)) DECODER.execute(() -> decode(imageFile));
        return null;
    }

    // background thread: read, scale to SIZE x SIZE
    private static void decode(Path imageFile) {
        NativeImage image = null;
        try {
            BufferedImage source = ImageIO.read(imageFile.toFile());
            if (source == null) throw new IOException("Unsupported image format (ImageIO can read: "
                    + String.join(", ", ImageIO.getReaderFileSuffixes()) + ")");

            BufferedImage thumb = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) {
                int sy = y * source.getHeight() / SIZE;
                for (int x = 0; x < SIZE; x++) {
                    thumb.setRGB(x, y, source.getRGB(x * source.getWidth() / SIZE, sy));
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(thumb, "png", png);
            image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not load cover image {}", imageFile, e); // once per file, see FAILED
        }
        READY.add(new Ready(imageFile, image));
    }

    // render thread
    private static void register(Ready ready) {
        LOADING.remove(ready.path());
        if (ready.image() == null) {
            FAILED.add(ready.path());
            return;
        }
        DynamicTexture texture = new DynamicTexture(ready.image());
        texture.setFilter(true, false); // smooth when scaled down
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "cover/" + nextId++);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        LOADED.put(ready.path(), id);
        // Release the covers shown longest ago. One that is asked for again is simply decoded again.
        for (Iterator<ResourceLocation> eldest = LOADED.values().iterator(); LOADED.size() > MAX_TEXTURES && eldest.hasNext(); ) {
            Minecraft.getInstance().getTextureManager().release(eldest.next());
            eldest.remove();
        }
    }

    /** Releases every cover texture (leaving a world). Render thread. */
    public static void clear() {
        for (ResourceLocation id : LOADED.values()) Minecraft.getInstance().getTextureManager().release(id);
        LOADED.clear();
    }
}
