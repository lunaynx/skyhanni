package at.hannibal2.skyhanni.mixins.transformers;

import at.hannibal2.skyhanni.mixins.hooks.ParticlePacketStore;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientboundLevelParticlesPacket.class)
public abstract class MixinClientboundLevelParticlesPacket implements ParticlePacketStore {
    @Unique
    boolean skyhanni$eventPosted = false;

    @Unique
    boolean skyhanni$cancelled = false;

    @Override
    public boolean skyhanni$isEventPosted() {
        return skyhanni$eventPosted;
    }

    @Override
    public void skyhanni$setEventPosted() {
        skyhanni$eventPosted = true;
    }

    @Override
    public boolean skyhanni$isCancelled() {
        return skyhanni$cancelled;
    }

    @Override
    public void skyhanni$setCancelled() {
        skyhanni$cancelled = true;
    }
}
