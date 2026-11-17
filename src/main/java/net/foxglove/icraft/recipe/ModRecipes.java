package net.foxglove.icraft.recipe;

import net.foxglove.icraft.iCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, iCraft.MOD_ID);

    // The recipe type used in the JSON files as "icraft_music:ipod_dye"
    public static final Supplier<RecipeSerializer<IpodDyeRecipe>> IPOD_DYE =
            SERIALIZERS.register("ipod_dye", IpodDyeRecipe.Serializer::new);

    public static void register(IEventBus eventBus) {
        SERIALIZERS.register(eventBus);
    }
}
