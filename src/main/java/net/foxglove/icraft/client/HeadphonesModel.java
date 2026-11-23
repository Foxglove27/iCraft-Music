package net.foxglove.icraft.client;

import net.foxglove.icraft.iCraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The headphones: a band over the head and two ear cups, in head coordinates (the head box is x -4..4, y -8..0,
 * z -4..4). Everything sits at least 1 unit outside the head, so it is drawn outside a helmet (armor is 1 unit thick).
 * The texture is 32x16; it is tinted with the playing iPod's colour when drawn.
 */
public class HeadphonesModel extends Model {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "headphones"), "main");
    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(iCraft.MOD_ID, "textures/entity/headphones.png");

    private final ModelPart root;

    public HeadphonesModel(ModelPart root) {
        super(RenderType::entityCutoutNoCull);
        this.root = root;
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition part = mesh.getRoot();
        part.addOrReplaceChild("band", CubeListBuilder.create().texOffs(0, 0).addBox(-6F, -10F, -0.5F, 12, 1, 1), PartPose.ZERO);
        part.addOrReplaceChild("arm_left", CubeListBuilder.create().texOffs(20, 2).addBox(5F, -10F, -0.5F, 1, 4, 1), PartPose.ZERO);
        part.addOrReplaceChild("arm_right", CubeListBuilder.create().texOffs(24, 2).addBox(-6F, -10F, -0.5F, 1, 4, 1), PartPose.ZERO);
        part.addOrReplaceChild("cup_left", CubeListBuilder.create().texOffs(0, 2).addBox(5F, -6F, -2F, 1, 4, 4), PartPose.ZERO);
        part.addOrReplaceChild("cup_right", CubeListBuilder.create().texOffs(10, 2).addBox(-6F, -6F, -2F, 1, 4, 4), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 16);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }
}
