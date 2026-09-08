package at.hannibal2.skyhanni.features.slayer.enderman

import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.ConfigUpdaterMigrator
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.SlayerApi
import at.hannibal2.skyhanni.events.ParticleEvent
import at.hannibal2.skyhanni.events.entity.EntityEnterWorldEvent
import at.hannibal2.skyhanni.events.entity.EntityLeaveWorldEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.LocationUtils.distanceTo
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.monster.EnderMan
import java.util.concurrent.ConcurrentHashMap

@SkyHanniModule
object EndermanSlayerHideParticles {

    // Tracked passively because onParticle runs on the network thread and must not query the entity list
    private val endermen = ConcurrentHashMap.newKeySet<EnderMan>()

    @HandleEvent
    private fun onEntityEnterWorld(event: EntityEnterWorldEvent<EnderMan>) {
        endermen += event.entity
    }

    @HandleEvent
    private fun onEntityLeaveWorld(event: EntityLeaveWorldEvent<EnderMan>) {
        endermen -= event.entity
    }

    @HandleEvent
    private fun onWorldChange() {
        endermen.clear()
    }

    @HandleEvent
    private fun onParticle(event: ParticleEvent) {
        if (!isEnabled()) return

        when (event.type) {
            ParticleTypes.LARGE_SMOKE,
            ParticleTypes.FLAME,
            ParticleTypes.WITCH,
            -> Unit

            else -> return
        }

        if (endermen.any { it.distanceTo(event.location) < 3.0 }) {
            event.cancel()
        }
    }

    fun isEnabled() = IslandType.THE_END.isInIsland() && SlayerApi.config.endermen.hideParticles

    @HandleEvent
    fun onConfigFix(event: ConfigUpdaterMigrator.ConfigFixEvent) {
        event.move(3, "slayer.endermanHideParticles", "slayer.endermen.hideParticles")
    }
}
