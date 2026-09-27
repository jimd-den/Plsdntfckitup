package com.stratum.feature.play

import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.sandbox.DummySpec
import com.stratum.core.domain.sandbox.ExplainedStat
import com.stratum.core.domain.sandbox.MeterReport
import com.stratum.core.domain.sandbox.StatBreakdown
import com.stratum.core.domain.sandbox.StatQuery

/** The sandbox panel's pages: make gear, shape the hero, set up targets, read the meter, read the build, share it. */
enum class SandboxTab(val label: String) {
    ITEMS("Items"),
    HERO("Hero"),
    TARGETS("Targets"),
    METER("Meter"),
    BREAKDOWN("Numbers"),
    BUILD("Share"),
}

/** Something in a pick list: its id, what to call it, and a second line. */
data class NamedChoice(val id: String, val name: String, val detail: String = "", val color: Long? = null)

/** Everything the sandbox panel draws. Lists are filled only for the page open, so a closed panel costs nothing. */
data class SandboxPanelState(
    /** This world is a sandbox: the dock shows the panel and the HUD shows the meter. */
    val active: Boolean = false,
    val open: Boolean = false,
    val tab: SandboxTab = SandboxTab.ITEMS,
    val capsLifted: Boolean = false,
    val slotFilter: ItemSlot? = null,
    val itemLevel: Int = DEFAULT_ITEM_LEVEL,
    val rarity: ItemRarity = ItemRarity.RARE,
    val bases: List<NamedChoice> = emptyList(),
    val uniques: List<NamedChoice> = emptyList(),
    val currencies: List<NamedChoice> = emptyList(),
    val supports: List<NamedChoice> = emptyList(),
    val dummy: DummySpec = DummySpec(),
    val dummies: Int = 0,
    val monsters: List<NamedChoice> = emptyList(),
    val meter: MeterReport = MeterReport(),
    /** Damage type ids to names and colours, for the meter's shares and the resistance picker. */
    val damageTypes: List<NamedChoice> = emptyList(),
    val query: StatQuery = StatQuery(ExplainedStat.DAMAGE),
    val breakdown: StatBreakdown? = null,
    /** The last exported build code, shown so it can be copied. */
    val exported: String? = null,
) {
    fun damageTypeName(id: String): String = damageTypes.firstOrNull { it.id == id }?.name ?: id.substringAfter(':')

    companion object {
        const val DEFAULT_ITEM_LEVEL = 60
    }
}

/**
 * The dummy presets a thumb can pick without typing: how much life, and how
 * well defended. The spec itself is the domain's; these are just its dials.
 */
object DummyDials {
    val life = listOf("1k" to 1_000, "10k" to DummySpec.STURDY_LIFE, "1m" to 1_000_000, "Huge" to DummySpec.HUGE_LIFE)
    val armour = listOf("None" to 0, "500" to 500, "5k" to 5_000, "50k" to 50_000)
    val evasion = listOf("None" to 0, "500" to 500, "5k" to 5_000)
    val block = listOf("None" to 0f, "25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f)
    val resistance = listOf("-50%" to -0.5f, "0%" to 0f, "40%" to 0.4f, "75%" to 0.75f, "100%" to 1f)
}
