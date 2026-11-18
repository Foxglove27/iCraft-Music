package net.foxglove.icraft.item;

import com.mojang.serialization.Codec;
import net.foxglove.icraft.iCraft;
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

    public static void register(IEventBus eventBus) {
        ATTACHMENTS.register(eventBus);
    }
}
