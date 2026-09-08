package at.hannibal2.skyhanni.features.rift.area.livingcave

import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.ConfigUpdaterMigrator
import at.hannibal2.skyhanni.events.ParticleEvent
import at.hannibal2.skyhanni.events.ServerBlockChangeEvent
import at.hannibal2.skyhanni.events.entity.EntityEnterWorldEvent
import at.hannibal2.skyhanni.events.entity.EntityLeaveWorldEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniRenderWorldEvent
import at.hannibal2.skyhanni.features.rift.RiftApi
import at.hannibal2.skyhanni.mixins.hooks.RenderLivingEntityHelper
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ColorUtils.addAlpha
import at.hannibal2.skyhanni.utils.ColorUtils.toColor
import at.hannibal2.skyhanni.utils.DelayedRun
import at.hannibal2.skyhanni.utils.EntityUtils.isAtFullHealth
import at.hannibal2.skyhanni.utils.LocationUtils.distanceTo
import at.hannibal2.skyhanni.utils.LorenzVec
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.editCopy
import at.hannibal2.skyhanni.utils.compat.deceased
import at.hannibal2.skyhanni.utils.compat.formattedTextCompatLessResets
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.draw3DLine
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawDynamicText
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawLineToCrosshair
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawWaypointFilled
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.exactLocation
import net.minecraft.client.player.RemotePlayer
import net.minecraft.core.particles.ParticleTypes
import java.util.concurrent.ConcurrentHashMap

@SkyHanniModule
object LivingCaveDefenseBlocks {

    private val config get() = RiftApi.config.area.livingCave.defenseBlock
    private var movingBlocks = mapOf<DefenseBlock, Long>()
    private var staticBlocks = emptyList<DefenseBlock>()

    // Tracked passively because onParticle runs on the network thread and must not query the entity list
    private val players = ConcurrentHashMap.newKeySet<RemotePlayer>()

    class DefenseBlock(val entity: RemotePlayer, val location: LorenzVec, var hidden: Boolean = false)

    @HandleEvent
    private fun onEntityEnterWorld(event: EntityEnterWorldEvent<RemotePlayer>) {
        players += event.entity
    }

    @HandleEvent
    private fun onEntityLeaveWorld(event: EntityLeaveWorldEvent<RemotePlayer>) {
        players -= event.entity
    }

    @HandleEvent
    private fun onWorldChange() {
        players.clear()
    }

    @HandleEvent
    private fun onSecondPassed() {
        if (!isEnabled()) return
        staticBlocks = staticBlocks.editCopy { removeIf { it.entity.deceased } }
    }

    @HandleEvent
    private fun onTick() {
        if (!isEnabled()) return
        movingBlocks = movingBlocks.editCopy {
            values.removeIf { System.currentTimeMillis() > it + 2000 }
            keys.removeIf { staticBlocks.any { others -> others.location.distance(it.location) < 1.5 } }
        }
    }

    // Runs on the network thread: only reads the copy-on-write collections here, mutations are deferred to the main thread
    @HandleEvent(receiveCancelled = true)
    private fun onParticle(event: ParticleEvent) {
        if (!isEnabled()) return

        val location = event.location.add(-0.5, 0.0, -0.5)

        // Ignore particles around blocks
        if (staticBlocks.any { it.location.distance(location) < 3 }) {
            if (config.hideParticles) {
                event.cancel()
            }
            return
        }
        if (config.hideParticles && movingBlocks.keys.any { it.location.distance(location) < 3 }) {
            event.cancel()
        }

        if (event.type == ParticleTypes.ENCHANTED_HIT) {
            // read old entity data
            val oldBlock = getNearestMovingDefenseBlock(location)?.takeIf { it.location.distance(location) < 0.5 }

            // read new entity data
            val compareLocation = event.location.add(-0.5, -1.5, -0.5)
            val entity = oldBlock?.entity ?: players
                .filter { it.distanceTo(compareLocation) < 2.0 }
                .filter { isCorrectMob(it.name.formattedTextCompatLessResets()) }
                .filter { !it.isAtFullHealth() }
                .minByOrNull { it.distanceTo(compareLocation) }
                ?: return

            if (config.hideParticles) {
                event.cancel()
            }
            DelayedRun.runOrNextTick {
                oldBlock?.hidden = true
                movingBlocks = movingBlocks.editCopy { this[DefenseBlock(entity, location)] = System.currentTimeMillis() + 250 }
            }
        }
    }

    private fun isCorrectMob(name: String) = when (name) {
        "Autonull ",

        "Autocap ",
        "Autochest ",
        "Autopants ",
        "Autoboots ",
        -> true

        else -> false
    }

    @HandleEvent
    fun onBlockChange(event: ServerBlockChangeEvent) {
        if (!isEnabled()) return
        val location = event.location
        val old = event.old
        val new = event.new

        // spawn block
        if (old == "air" && (new == "stained_glass" || new == "diamond_block")) {
            val entity = getNearestMovingDefenseBlock(location)?.entity ?: return
            staticBlocks = staticBlocks.editCopy {
                add(DefenseBlock(entity, location))
                RenderLivingEntityHelper.setEntityColor(
                    entity,
                    color.addAlpha(50),
                ) { isEnabled() && staticBlocks.any { it.entity == entity } }
            }
        }

        // despawn block
        val nearestBlock = getNearestStaticDefenseBlock(location)
        if (new == "air" && location == nearestBlock?.location) {
            staticBlocks = staticBlocks.editCopy { remove(nearestBlock) }
        }
    }

    private fun getNearestMovingDefenseBlock(location: LorenzVec) =
        movingBlocks.keys.filter { it.location.distance(location) < 15 }
            .minByOrNull { it.location.distance(location) }

    private fun getNearestStaticDefenseBlock(location: LorenzVec) =
        staticBlocks.filter { it.location.distance(location) < 15 }.minByOrNull { it.location.distance(location) }

    @HandleEvent
    fun onRenderWorld(event: SkyHanniRenderWorldEvent) {
        if (!isEnabled()) return

        for ((block, time) in movingBlocks) {
            if (block.hidden) continue
            if (time > System.currentTimeMillis()) {
                val location = block.location
                event.drawWaypointFilled(location, color)
                event.drawLineToCrosshair(
                    location.blockCenter(),
                    color,
                    1,
                    false,
                )
            }
        }
        for (block in staticBlocks) {
            val location = block.location
            event.drawDynamicText(location, "§bBreak!", 1.5, seeThroughBlocks = false)
            event.drawWaypointFilled(location, color)

            event.draw3DLine(
                event.exactLocation(block.entity).up(0.5),
                location.blockCenter(),
                color,
                3,
                true,
            )
        }
    }

    private val color get() = config.color.get().toColor()

    fun isEnabled() = RiftApi.inRift() && config.enabled && RiftApi.inLivingCave()

    @HandleEvent
    fun onConfigFix(event: ConfigUpdaterMigrator.ConfigFixEvent) {
        event.move(9, "rift.area.livingCaveConfig", "rift.area.livingCave")

        val basePath = "rift.area.livingCave"
        event.move(82, "$basePath.defenseBlockConfig", "$basePath.defenseBlock")
        event.move(82, "$basePath.livingCaveLivingMetalConfig", "$basePath.livingMetal")
    }
}
