package net.foxglove.icraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.foxglove.icraft.block.DockBlock;
import net.foxglove.icraft.block.DockBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws the iPod that sits in a docking station, leaning against its back rest. */
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
    }
}
