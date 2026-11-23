package net.foxglove.icraft.item;

import com.mojang.serialization.Codec;
import net.foxglove.icraft.iCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Data attached to an entity (here: a player) that is saved with it. Different from a data component, which belongs
 * to an item stack.
 */
public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, iCraft.MOD_ID);

    /** True once the player was given the manual. Saved with the player; kept when they die. */
    public static final Supplier<AttachmentType<Boolean>> MANUAL_GIVEN = ATTACHMENTS.register("manual_given",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

    /**
     * What other players see of a player's listening (B18): -1 = nothing, else the ordinal of the {@link IpodColor}
     * of the iPod that plays on them (headphones are drawn). Not saved, not kept on death; synced to the players that
     * see the holder, also late joiners. Set by the player's client through {@code ListeningPayload}.
     */
    public static final Supplier<AttachmentType<Integer>> HEADPHONES = ATTACHMENTS.register("headphones",
            () -> AttachmentType.builder(() -> -1).sync(ByteBufCodecs.VAR_INT).build());

    public static void register(IEventBus eventBus) {
        ATTACHMENTS.register(eventBus);
    }
}
