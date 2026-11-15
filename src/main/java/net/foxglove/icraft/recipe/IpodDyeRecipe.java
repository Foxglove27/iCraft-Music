package net.foxglove.icraft.recipe;

import com.mojang.serialization.MapCodec;
import net.foxglove.icraft.item.IpodItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * "Any iPod + a dye = an iPod of that colour", which keeps everything stored on the old iPod
 * (loaded discs, the linked speaker, a custom name, and anything we add in the future).
 *
 * It is a normal shapeless recipe in every way except {@link #assemble}, so recipe viewers
 * such as JEI and EMI can still show its ingredients.
 */
public class IpodDyeRecipe extends ShapelessRecipe {

    // Builds our recipe from the ordinary shapeless recipe the JSON file describes
    public IpodDyeRecipe(ShapelessRecipe base) {
        super(base.getGroup(), base.category(), base.getResultItem(RegistryAccess.EMPTY), base.getIngredients());
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (!super.matches(input, level)) return false;

        // Dyeing an iPod with its own colour would only waste the dye
        ItemStack source = findIpod(input);
        return source.isEmpty() || !source.is(getResultItem(RegistryAccess.EMPTY).getItem());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = super.assemble(input, registries); // a fresh iPod in the new colour

        // Copy everything that is different from a fresh iPod onto the result
        ItemStack source = findIpod(input);
        if (!source.isEmpty()) {
            result.applyComponents(source.getComponentsPatch());
        }
        return result;
    }

    // The iPod that is being dyed
    private static ItemStack findIpod(CraftingInput input) {
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.getItem() instanceof IpodItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.IPOD_DYE.get();
    }

    // Reads and sends the recipe exactly like a shapeless recipe, but creates our class
    public static class Serializer implements RecipeSerializer<IpodDyeRecipe> {
        private static final MapCodec<IpodDyeRecipe> CODEC =
                RecipeSerializer.SHAPELESS_RECIPE.codec().xmap(IpodDyeRecipe::new, recipe -> recipe);

        private static final StreamCodec<RegistryFriendlyByteBuf, IpodDyeRecipe> STREAM_CODEC =
                RecipeSerializer.SHAPELESS_RECIPE.streamCodec().map(IpodDyeRecipe::new, recipe -> recipe);

        @Override
        public MapCodec<IpodDyeRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, IpodDyeRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
