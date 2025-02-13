package at.hannibal2.skyhanni.features.misc

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.data.IslandGraphs
import at.hannibal2.skyhanni.data.model.Graph
import at.hannibal2.skyhanni.data.model.GraphNode
import at.hannibal2.skyhanni.data.model.GraphNodeTag
import at.hannibal2.skyhanni.data.model.TextInput
import at.hannibal2.skyhanni.data.model.findShortestPathAsGraphWithDistance
import at.hannibal2.skyhanni.events.ConfigLoadEvent
import at.hannibal2.skyhanni.events.EntityMoveEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.LorenzRenderWorldEvent
import at.hannibal2.skyhanni.events.LorenzTickEvent
import at.hannibal2.skyhanni.events.LorenzWorldChangeEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.CollectionUtils.addSearchString
import at.hannibal2.skyhanni.utils.CollectionUtils.sorted
import at.hannibal2.skyhanni.utils.ColorUtils.toChromaColor
import at.hannibal2.skyhanni.utils.ConditionalUtils
import at.hannibal2.skyhanni.utils.LocationUtils.canBeSeen
import at.hannibal2.skyhanni.utils.LocationUtils.distanceToPlayer
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.LorenzUtils.round
import at.hannibal2.skyhanni.utils.RenderUtils.drawDynamicText
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderable
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.renderables.Searchable
import at.hannibal2.skyhanni.utils.renderables.buildSearchBox
import at.hannibal2.skyhanni.utils.renderables.toSearchable
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object IslandAreas {
    private val config get() = SkyHanniMod.feature.misc.areaNavigation

    private var nodes = mapOf<GraphNode, Double>()
    private var paths = mapOf<GraphNode, Graph>()
    private var display: Renderable? = null
    private var targetNode: GraphNode? = null
    private var currentAreaName = ""
    private val textInput = TextInput()

    @SubscribeEvent
    fun onWorldChange(event: LorenzWorldChangeEvent) {
        nodes = emptyMap()
        display = null
        targetNode = null
        hasMoved = true
    }

    fun noteMoved() {
        SkyHanniMod.coroutineScope.launch {
            updateNodes()
        }
    }

    private fun updateNodes() {
        if (!isEnabled()) return
        val graph = IslandGraphs.currentIslandGraph ?: return
        val closedNote = IslandGraphs.closedNote ?: return

        val paths = mutableMapOf<GraphNode, Graph>()

        val map = mutableMapOf<GraphNode, Double>()
        for (graphNode in graph.nodes) {
            if (graphNode.getAreaTag() == null) continue
            val (path, distance) = graph.findShortestPathAsGraphWithDistance(closedNote, graphNode)
            paths[graphNode] = path
            map[graphNode] = distance
        }
        this.paths = paths

        val finalNodes = mutableMapOf<GraphNode, Double>()

        val alreadyFoundAreas = mutableListOf<String>()
        for ((node, distance) in map.sorted()) {
            val areaName = node.name ?: continue
            if (areaName in alreadyFoundAreas) continue
            alreadyFoundAreas.add(areaName)

            finalNodes[node] = distance
        }

        nodes = finalNodes
    }

    var hasMoved = false

    @SubscribeEvent
    fun onTick(event: LorenzTickEvent) {
        if (!LorenzUtils.inSkyBlock) return
        if (!IslandGraphs.existsForThisIsland) return

        if (event.isMod(2) && hasMoved) {
            hasMoved = false
            updatePosition()
        }
    }

    @SubscribeEvent
    fun onPlayerMove(event: EntityMoveEvent) {
        if (isEnabled()) {
            if (event.entity == Minecraft.getMinecraft().thePlayer) {
                hasMoved = true
            }
        }
    }

    private fun updatePosition() {
        display = buildDisplay().buildSearchBox(textInput)
    }

    @SubscribeEvent
    fun onOverlay(event: GuiRenderEvent.GuiOverlayRenderEvent) {
        if (!isEnabled()) return
        if (!config.pathfinder.enabled) return
        if (!config.pathfinder.showAlways) return

        display?.let {
            config.pathfinder.position.renderRenderable(it, posLabel = "Island Areas")
        }
    }

    @SubscribeEvent
    fun onOverlay(event: GuiRenderEvent.ChestGuiOverlayRenderEvent) {
        if (!isEnabled()) return
        if (!config.pathfinder.enabled) return

        display?.let {
            config.pathfinder.position.renderRenderable(it, posLabel = "Island Areas")
        }
    }

    private fun buildDisplay() = buildList<Searchable> {
        val closedNote = IslandGraphs.closedNote ?: return@buildList
        val playerDiff = closedNote.position.distanceToPlayer()

        var foundCurrentArea = false
        var foundAreas = 0

        for ((node, diff) in nodes) {
            val difference = diff + playerDiff
            val tag = node.getAreaTag() ?: continue

            val name = node.name ?: continue
            // can not compare nodes directly. By using names, we also accept other nodes
            val isTarget = node.name == targetNode?.name
            val color = if (isTarget) LorenzColor.GOLD else tag.color

            // trying to find a faster path to the existing target
            if (isTarget && node != targetNode) {
                ChatUtils.debug("Found a faster node, rerouting...")
                setTarget(node)
            }
            val coloredName = "${color.getChatColor()}${name}"

            var suffix = ""
            paths[node]?.let { path ->
                val passedAreas = path.nodes.filter { it.getAreaTag() != null }.map { it.name }.distinct().toMutableList()
                passedAreas.remove(name)
                passedAreas.remove(null)
                passedAreas.remove("null")
                passedAreas.remove(currentAreaName)
                // so show areas needed to pass thorough
                if (passedAreas.isNotEmpty()) {
//                     suffix = " §7${passedAreas.joinToString(", ")}"
                }
            }

            val distance = difference.round(1)
            val text = "${coloredName}§7: §e$distance$suffix"

            if (!foundCurrentArea) {
                foundCurrentArea = true

                val inAnArea = name != "no_area"
                if (config.pathfinder.includeCurrentArea.get()) {
                    if (inAnArea) {
                        addSearchString("§eCurrent area: $coloredName")
                    } else {
                        addSearchString("§7Not in an area.")
                    }
                }
                if (name != currentAreaName) {
                    if (inAnArea && config.enterTitle) {
                        LorenzUtils.sendTitle("§aEntered $name!", 3.seconds)
                    }
                    currentAreaName = name
                }

                addSearchString("§eAreas nearby:")
                continue
            }

            if (name == "no_area") continue
            foundAreas++

            add(
                Renderable.clickAndHover(
                    text,
                    tips = buildList {
                        add(tag.color.getChatColor() + node.name)
                        add("§7Type: ${tag.displayName}")
                        add("§7Distance: §e$distance blocks")
                        add("")
                        if (node == targetNode) {
                            add("§aPath Finder points to this!")
                            add("")
                            add("§eClick to disable!")
                        } else {
                            add("§eClick to find a path!")
                        }
                    },
                    onClick = {
                        if (node == targetNode) {
                            targetNode = null
                            IslandGraphs.stop()
                            updatePosition()
                        } else {
                            setTarget(node)
                        }
                    },
                ).toSearchable(name),
            )
        }
        if (foundAreas == 0) {
            val islandName = LorenzUtils.skyBlockIsland.displayName
            if (foundCurrentArea) {
                addSearchString("§cThere is only one area in $islandName,")
                addSearchString("§cnothing else to navigate to!")
            } else {
                addSearchString("§cThere is no $islandName area data avaliable yet!")
            }
        }
    }

    @SubscribeEvent
    fun onRenderWorld(event: LorenzRenderWorldEvent) {
        if (!LorenzUtils.inSkyBlock) return
        if (!config.inWorld) return
        for ((node, distance) in nodes) {
            val name = node.name ?: continue
            if (name == currentAreaName) continue
            if (name == "no_area") continue
            val position = node.position
            val color = node.getAreaTag()?.color?.getChatColor() ?: ""
            if (!position.canBeSeen(40.0)) return
            event.drawDynamicText(position, color + name, 1.5)
        }
    }

    @SubscribeEvent
    fun onConfigLoad(event: ConfigLoadEvent) {
        ConditionalUtils.onToggle(config.pathfinder.color) {
            targetNode?.let {
                setTarget(it)
            }
        }
        ConditionalUtils.onToggle(config.pathfinder.color, config.pathfinder.includeCurrentArea) {
            updateNodes()
            updatePosition()
        }
    }

    private val allAreas = listOf(GraphNodeTag.AREA, GraphNodeTag.SMALL_AREA)
    private val onlyLargeAreas = listOf(GraphNodeTag.AREA)

    private fun GraphNode.getAreaTag(): GraphNodeTag? = tags.firstOrNull {
        it in (if (config.includeSmallAreas) allAreas else onlyLargeAreas)
    }

    private fun setTarget(node: GraphNode) {
        targetNode = node
        val color = config.pathfinder.color.get().toChromaColor()
        IslandGraphs.pathFind(
            node.position, color,
            onFound = {
                targetNode = null
                updatePosition()
            },
            condition = { config.pathfinder.enabled },
        )
        hasMoved = true
    }

    fun isEnabled() = LorenzUtils.inSkyBlock && config.let { it.pathfinder.enabled || it.enterTitle || it.inWorld }
}
