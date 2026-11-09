package dev.donut.client.mixin;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Keep unresolved IDs for diagnostics without assigning an arbitrary nearby entity. */
@Mixin(ClientboundEntityEventPacket.class)
public interface SoundEntityEventAccessor {
    @Accessor("entityId") int donut$soundEntityId();
}
