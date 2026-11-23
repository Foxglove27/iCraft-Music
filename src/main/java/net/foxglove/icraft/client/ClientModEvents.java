package net.foxglove.icraft.client;

import net.foxglove.icraft.block.ModBlockEntities;
import net.foxglove.icraft.iCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only setup on the mod event bus. */
@EventBusSubscriber(modid = iCraft.MOD_ID, value = Dist.CLIENT)
public class ClientModEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.DOCK.get(), DockRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(HeadphonesModel.LAYER, HeadphonesModel::createLayer);
    }

    /** Headphones on the default and slim player models (B18). */
    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (net.minecraft.client.resources.PlayerSkin.Model skin : event.getSkins()) {
            if (event.getSkin(skin) instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer renderer) {
                renderer.addLayer(new HeadphonesLayer(renderer, new HeadphonesModel(event.getEntityModels().bakeLayer(HeadphonesModel.LAYER))));
            }
        }
    }
}
