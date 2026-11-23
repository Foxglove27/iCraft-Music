package net.foxglove.icraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.foxglove.icraft.block.DockBlock;
import net.foxglove.icraft.block.DockBlockEntity;
import net.foxglove.icraft.client.screen.Marquee;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the iPod that sits in a docking station, leaning against its back rest, and (B14) a small label above a
 * playing dock that turns to the viewer like a name tag: title, album or "Music Discs", and whose dock it is.
 */
public class DockRenderer implements BlockEntityRenderer<DockBlockEntity> {
    private final BlockEntityRendererProvider.Context context;

    public DockRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    @Override
    public void render(DockBlockEntity dock, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        ItemStack ipod = dock.getIpod();
        if (ipod.isEmpty()) return;

        pose.pushPose();
        pose.translate(0.5, -0.05, 0.5);
        // turn with the dock so the iPod faces the front (the block model faces north)
        pose.mulPose(Axis.YP.rotationDegrees(-dock.getBlockState().getValue(DockBlock.FACING).toYRot() + 180));
        pose.translate(0.0, 0.5, 0.06);              // above the base, near the middle, top resting on the back rest
        pose.mulPose(Axis.XP.rotationDegrees(12));  // leans back a little
        pose.scale(0.62F, 0.62F, 0.62F);
        context.getItemRenderer().renderStatic(ipod, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, dock.getLevel(), (int) dock.getBlockPos().asLong());
        pose.popPose();

        renderLabel(dock, pose, buffers);
    }

    private static final double LABEL_DISTANCE = 16;    // blocks: farther away, no label
    private static final int LABEL_MAX_WIDTH = 140;     // font pixels; longer lines scroll
    private static final int FULL_BRIGHT = 0xF000F0;    // the label is readable in the dark too
    private static final float LABEL_SCALE = 0.016F;    // world size of one font pixel (a name tag is 0.025)

    // The label above a playing dock, drawn like a vanilla name tag (so it works with Sodium and shader packs)
    private void renderLabel(DockBlockEntity dock, PoseStack pose, MultiBufferSource buffers) {
        if (!IpodSettings.dockDisplay()) return;
        Vec3 camera = context.getBlockEntityRenderDispatcher().camera.getPosition();
        if (camera.distanceToSqr(Vec3.atCenterOf(dock.getBlockPos())) > LABEL_DISTANCE * LABEL_DISTANCE) return;
        DockClientPlayer.Heard shown = DockClientPlayer.describe(dock);
        if (shown == null) return;

        // the lines: title (with a note, or "(paused)"), album or state, whose dock
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        // another player's own local files: line 1 already names the owner (the owner sees their real title instead)
        net.minecraft.world.entity.player.Player viewer = Minecraft.getInstance().player;
        boolean ownMusic = dock.isExternalPlaying() && (dock.getPlayback() == null || !dock.getPlayback().isPlaying())
                && (viewer == null || !dock.isOwner(viewer));
        Component title = shown.paused()
                ? Component.empty().append(shown.title()).append(Component.translatable("hud.icraft_music.paused"))
                : Component.literal("♪ ").append(shown.title());
        lines.add(title);
        colors.add(0xFFFFFFFF);
        if (shown.muted()) {
            lines.add(Component.translatable("screen.icraft_music.ipod.muted"));
            colors.add(0xFFFF8080);
        } else if (shown.waiting()) {
            lines.add(Component.translatable("hud.icraft_music.waiting"));
            colors.add(0xFFAAAAAA);
        } else if (!shown.subtitle().getString().isEmpty()) {
            lines.add(shown.subtitle());
            colors.add(0xFFAAAAAA);
        }
        if (!ownMusic && !shown.owner().isEmpty()) { // own music already names the owner in the first line
            lines.add(Component.translatable("hud.icraft_music.from", shown.owner()));
            colors.add(0xFFAAAAAA);
        }

        Font font = context.getFont();
        List<String> texts = new ArrayList<>();
        int width = 0;
        Marquee.Clip[] clips = CLIPS.computeIfAbsent(dock, d -> new Marquee.Clip[]{new Marquee.Clip(), new Marquee.Clip(), new Marquee.Clip()});
        for (int i = 0; i < lines.size(); i++) {
            String text = clips[i].visible(font, lines.get(i).getString(), LABEL_MAX_WIDTH); // too long: it scrolls
            texts.add(text);
            width = Math.max(width, font.width(text));
        }

        pose.pushPose();
        pose.translate(0.5, 1.2, 0.5);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        Matrix4f matrix = pose.last().pose();
        int lineH = font.lineHeight + 1;
        int top = -texts.size() * lineH; // the lines grow upwards from the anchor

        // one background box behind all lines (a box per line overlapped and flickered with the text)
        float left = -width / 2.0F - 2, right = width / 2.0F + 2;
        float boxTop = top - 2, boxBottom = top + texts.size() * lineH + 1;
        VertexConsumer box = buffers.getBuffer(RenderType.textBackground());
        // same corner order as the vanilla font's background boxes
        box.addVertex(matrix, left, boxTop, 0).setColor(0x40000000).setLight(FULL_BRIGHT);
        box.addVertex(matrix, right, boxTop, 0).setColor(0x40000000).setLight(FULL_BRIGHT);
        box.addVertex(matrix, right, boxBottom, 0).setColor(0x40000000).setLight(FULL_BRIGHT);
        box.addVertex(matrix, left, boxBottom, 0).setColor(0x40000000).setLight(FULL_BRIGHT);

        // the text in front of the box (POLYGON_OFFSET pulls it towards the viewer, like vanilla text displays)
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            font.drawInBatch(text, -font.width(text) / 2.0F, top + i * lineH, colors.get(i), false, matrix, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, FULL_BRIGHT);
        }
        pose.popPose();
    }

    // the scrolling state of each dock's label lines; weak, so a removed dock is forgotten
    private static final java.util.Map<DockBlockEntity, Marquee.Clip[]> CLIPS = new java.util.WeakHashMap<>();

    // The label sits above the block: render while the space above it is on screen, not only the dock itself
    @Override
    public @NotNull AABB getRenderBoundingBox(DockBlockEntity dock) {
        return new AABB(dock.getBlockPos()).expandTowards(0, 2, 0);
    }
}
