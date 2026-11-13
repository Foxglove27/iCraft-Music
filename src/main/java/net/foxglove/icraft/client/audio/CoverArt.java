package net.foxglove.icraft.client.audio;

import com.mojang.blaze3d.platform.NativeImage;
import net.foxglove.icraft.iCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CoverArt {
    public static final int SIZE = 128;

    private static final Map<Path, ResourceLocation> LOADED = new HashMap<>();
    private static final Set<Path> FAILED = new HashSet<>();
    private static int nextId = 0;

    // Must be called on the render thread (e.g. from a screen's render method)
    @Nullable
    public static ResourceLocation get(Path imageFile) {
        ResourceLocation cached = LOADED.get(imageFile);
        if (cached != null) return cached;
        if (FAILED.contains(imageFile)) return null;

        try {
            BufferedImage source = ImageIO.read(imageFile.toFile());
            if (source == null) throw new IOException("Unsupported image format");

            BufferedImage thumb = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) {
                int sy = y * source.getHeight() / SIZE;
                for (int x = 0; x < SIZE; x++) {
                    thumb.setRGB(x, y, source.getRGB(x * source.getWidth() / SIZE, sy));
                }
            }

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(thumb, "png", png);
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));

            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(true, false); // smooth when scaled down
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "cover/" + nextId++);
            Minecraft.getInstance().getTextureManager().register(id, texture);

            LOADED.put(imageFile, id);
            return id;
        } catch (IOException | RuntimeException e) {
            FAILED.add(imageFile);
            return null;
        }
    }
}