package at.hannibal2.skyhanni.utils

import at.hannibal2.skyhanni.events.ParticleEvent
import at.hannibal2.skyhanni.utils.compat.MinecraftCompat
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.resources.Identifier

object ParticleUtils {
    fun getParticleTypeByName(name: String): Identifier? {
        val id = Identifier.tryParse(name.lowercase()) ?: return null
        if (!BuiltInRegistries.PARTICLE_TYPE.containsKey(id)) {
            return null
        }
        return id
    }

    @JvmStatic
    fun postParticleEvent(packet: ClientboundLevelParticlesPacket) {
        if (!MinecraftCompat.localPlayerExists) return
        // handleParticleEvent runs once on the Netty thread and again on the main thread for the same packet
        if (packet.`skyhanni$isEventPosted`()) return
        packet.`skyhanni$setEventPosted`()
        val event = ParticleEvent(
            type = packet.particle.type,
            location = packet.toLorenzVec(),
            count = packet.count,
            speed = packet.maxSpeed,
            offset = packet.toOffset(),
            longDistance = packet.isOverrideLimiter,
        )
        if (event.post().isCancelled) {
            packet.`skyhanni$setCancelled`()
        }
    }

    @JvmStatic
    fun shouldSuppressParticle(packet: ClientboundLevelParticlesPacket): Boolean = packet.`skyhanni$isCancelled`()
}
