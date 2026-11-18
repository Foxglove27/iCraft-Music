package net.foxglove.icraft.block;

import net.foxglove.icraft.iCraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Taking the iPod out of a dock is "sneak + right-click with an empty hand". The game skips a block's own interaction
 * when the player sneaks with ANYTHING in either hand, so with an item in the off hand the dock never saw the click.
 * This forces the block interaction for exactly that case.
 */
@EventBusSubscriber(modid = iCraft.MOD_ID)
public class DockInteractionEvents {
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() == InteractionHand.MAIN_HAND
                && event.getEntity().isShiftKeyDown()
                && event.getItemStack().isEmpty()
                && event.getLevel().getBlockState(event.getPos()).is(ModBlocks.DOCK.get())) {
            event.setUseBlock(TriState.TRUE);
        }
    }
}
