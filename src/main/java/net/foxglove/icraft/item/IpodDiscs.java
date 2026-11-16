package net.foxglove.icraft.item;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The music discs loaded into an iPod, stored as the iPod's own data component.
 * We don't reuse the vanilla "container" component, so other mods don't mistake an iPod
 * for a shulker-box-style container.
 *
 * The equals/hashCode methods matter: item stacks are compared component by component
 * (recipes, merging, the creative tabs), and plain ItemStacks don't compare by contents.
 */
public record IpodDiscs(List<ItemStack> discs) {
    public static final IpodDiscs EMPTY = new IpodDiscs(List.of());

    public static final Codec<IpodDiscs> CODEC =
            ItemStack.CODEC.listOf().xmap(IpodDiscs::new, IpodDiscs::discs);

    public static final StreamCodec<RegistryFriendlyByteBuf, IpodDiscs> STREAM_CODEC =
            ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()).map(IpodDiscs::new, IpodDiscs::discs);

    // Keep our own copies, so nobody can change the stored discs by accident
    public IpodDiscs {
        discs = discs.stream().map(ItemStack::copy).toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IpodDiscs that && ItemStack.listMatches(this.discs, that.discs);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashStackList(this.discs);
    }
}
