package at.hannibal2.skyhanni.features.misc

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.ProfileStorageData
import at.hannibal2.skyhanni.events.ConfigLoadEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.LorenzChatEvent
import at.hannibal2.skyhanni.features.bazaar.BazaarApi
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.LorenzUtils.addAsSingletonList
import at.hannibal2.skyhanni.utils.LorenzUtils.editCopy
import at.hannibal2.skyhanni.utils.NEUItems
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
import at.hannibal2.skyhanni.utils.NumberUtil.format
import at.hannibal2.skyhanni.utils.RenderUtils.renderStringsAndItems
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

class EnderNodeTracker {
    private val config get() = SkyHanniMod.feature.misc.enderNodeTracker;
    private val storage = ProfileStorageData.profileSpecific?.enderNodeTracker

    private var totalEnderArmor = 0
    private var display = emptyList<List<Any>>()
    private var lootProfit = mapOf<EnderNode, Double>()

    private val enderNodeRegex = Regex("""ENDER NODE!.+You found (\d+x )?§r(.+)§r§f!""")
    private val endermanRegex = Regex("""(RARE|PET) DROP! §r(.+) §r§b\(""")

    @SubscribeEvent
    fun onChat(event: LorenzChatEvent) {
        if (!ProfileStorageData.loaded) return
        if (!inTheEnd()) return
        val hidden = storage ?: return
        val lootCount = hidden.lootCount
        // don't call removeColor because we want to distinguish enderman pet rarity
        val message = event.message.trim()
        var firstItem: String? = null
        var amount = 1

        // check whether the loot is from an ender node or an enderman
        enderNodeRegex.find(message)?.let {
            hidden.totalNodesMined++
            amount = it.groups[1]?.value?.substringBefore("x")?.toIntOrNull() ?: 1
            firstItem = it.groups[2]?.value
        } ?: endermanRegex.find(message)?.let {
            amount = 1
            firstItem = it.groups[2]?.value
        }

        var finalItem = firstItem ?: return

        when {
            isEnderArmor(finalItem) -> totalEnderArmor++

            finalItem == "§cEndermite Nest" -> {
                // this is oversimplified, it assumes an average of 3 nest endermites killed per nest dug up
                hidden.totalEndermiteNests++
                val oldEndStone = lootCount[EnderNode.ENCHANTED_ENDSTONE] ?: 0
                val oldMiteGel = lootCount[EnderNode.MITE_GEL] ?: 0
                hidden.lootCount = lootCount.editCopy {
                    this[EnderNode.ENCHANTED_ENDSTONE] = oldEndStone + 3
                    this[EnderNode.MITE_GEL] = oldMiteGel + 3
                }
            }
        }

        // increment the count of the specific item found
        EnderNode.entries.find { it.displayName == finalItem }?.let {
            val old = lootCount[it] ?: 0
            hidden.lootCount = lootCount.editCopy {
                this[it] = old + amount
            }
        }
        saveAndUpdate()
    }

    @SubscribeEvent
    fun onRenderOverlay(event: GuiRenderEvent.GameOverlayRenderEvent) {
        if (!config.enabled) return
        if (config.onlyInTheEnd && !inTheEnd()) return
        config.position.renderStringsAndItems(display, posLabel = "Ender Node Tracker")
    }

    @SubscribeEvent
    fun onConfigLoad(event: ConfigLoadEvent) {
        val hidden = storage ?: return
        totalEnderArmor = hidden.lootCount.filter { isEnderArmor(it.key.displayName) }.map { it.value }.sum()
        saveAndUpdate()
    }

    private fun calculateProfit(): Map<EnderNode, Double> {
        val newProfit = mutableMapOf<EnderNode, Double>()
        val lootCount = storage?.lootCount
        lootCount?.forEach {
            val price = if (isEnderArmor(it.key.displayName)) {
                10_000.0
            } else if (LorenzUtils.noTradeMode) {
                NEUItems.getPrice(it.key.internalName)
            } else {
                val bzData = BazaarApi.getBazaarDataByInternalName(it.key.internalName)
                bzData?.npcPrice?.coerceAtLeast(bzData.sellPrice) ?: NEUItems.getPrice(it.key.internalName)
            }
            newProfit[it.key] = price * (lootCount[it.key] ?: 0)
        }
        return newProfit
    }

    private fun saveAndUpdate() {
        lootProfit = calculateProfit()
        display = formatDisplay(drawDisplay())
    }

    private fun inTheEnd() = LorenzUtils.inIsland(IslandType.THE_END)

    private fun isEnderArmor(displayName: String) = when (displayName) {
        "§5Ender Helmet",
        "§5Ender Chestplate",
        "§5Ender Leggings",
        "§5Ender Boots",
        "§5Ender Necklace",
        "§5Ender Gauntlet" -> true

        else -> false
    }

    private fun drawDisplay() = buildList<List<Any>> {
        val hidden = storage ?: return emptyList<List<Any>>()
        val lootCount = hidden.lootCount
        val totalNodesMined = hidden.totalNodesMined
        val totalEndermiteNests = hidden.totalEndermiteNests

        addAsSingletonList("§5§lEnder Node Tracker")
        addAsSingletonList("§d${totalNodesMined.addSeparators()} Ender Nodes Mined")
        addAsSingletonList("§6${format(lootProfit.values.sum())} Coins Made")
        addAsSingletonList(" ")
        addAsSingletonList("§b${totalEndermiteNests.addSeparators()} §cEndermite Nest")

        for (item in EnderNode.entries.subList(0, 11)) {
            val count = lootCount[item] ?: 0
            val profit = format(lootProfit[item] ?: 0.0)
            addAsSingletonList("§b$count ${item.displayName} §f(§6$profit§f)")
        }
        addAsSingletonList(" ")
        addAsSingletonList("§b$totalEnderArmor §5Ender Armor §f(§6${format(totalEnderArmor * 10_000)}§f)")
        for (item in EnderNode.entries.subList(11, 16)) {
            val count = lootCount[item] ?: 0
            val profit = format(lootProfit[item] ?: 0.0)
            addAsSingletonList("§b$count ${item.displayName} §f(§6$profit§f)")
        }
        // enderman pet rarities
        val (c, u, r, e, l) = EnderNode.entries.subList(16, 21).map { lootCount[it] ?: 0 }
        val profit = format(EnderNode.entries.subList(16, 21).sumOf { lootProfit[it] ?: 0.0 })
        addAsSingletonList("§f$c§7-§a$u§7-§9$r§7-§5$e§7-§6$l §fEnderman Pet §f(§6$profit§f)")
    }

    private fun formatDisplay(map: List<List<Any>>): List<List<Any>> {
        val newList = mutableListOf<List<Any>>()
        for (index in config.textFormat) {
            newList.add(map[index])
        }
        return newList
    }
}