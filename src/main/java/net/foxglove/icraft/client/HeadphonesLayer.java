package net.foxglove.icraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.foxglove.icraft.item.IpodColor;
import net.foxglove.icraft.item.ModAttachments;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Draws headphones on the head of a player while music plays on them (B18). The state is the synced attachment
 * {@link ModAttachments#HEADPHONES}; nothing is drawn in first person (the own model is not drawn there), for
 * invisible players, or when the viewer switched "Headphones" off in the iPod's Settings.
 */
public class HeadphonesLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final HeadphonesModel model;

    public HeadphonesLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, HeadphonesModel model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (player.isInvisible() || !IpodSettings.showHeadphones()) return;
        int ordinal = player.getData(ModAttachments.HEADPHONES.get());
        IpodColor[] colors = IpodColor.values();
        if (ordinal < 0 || ordinal >= colors.length) return;

        poseStack.pushPose();
        getParentModel().head.translateAndRotate(poseStack); // follows the head: turning, nodding, sneaking
        model.renderToBuffer(poseStack, buffer.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(HeadphonesModel.TEXTURE)),
                packedLight, OverlayTexture.NO_OVERLAY, 0xFF000000 | colors[ordinal].tint());
        poseStack.popPose();
    }
}
