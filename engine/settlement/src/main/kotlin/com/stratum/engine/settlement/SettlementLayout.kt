package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.PlacedBuilding
import com.stratum.core.domain.settlement.Road
import com.stratum.core.domain.settlement.SettlementRecipe
import kotlin.random.Random

/** Where a town will stand and how big it will be, decided before its layout is. */
data class SettlementSite(
    val centerX: Int,
    val centerY: Int,
    val groundZ: Int,
    val radius: Int,
)

/** What a layout decides: the roads and the buildings. The town's shape and ground are the site's. */
data class Layout(
    val roads: List<Road>,
    val buildings: List<PlacedBuilding>,
    /** Square towns measure their wall by the larger of the two axes, not by a circle. */
    val square: Boolean = false,
)

/**
 * How a town's pieces are arranged: the street pattern and where buildings go.
 *
 * The seam for code plugins. A recipe names a layout by id; register a new
 * one and any pack can ask for it -- a hive city's stacked tiers, a river
 * port along a bank, a monastery on a hill. Layouts must be deterministic in
 * the [Random] they are handed, since every chunk a town overlaps asks for
 * the same town.
 */
interface SettlementLayout {
    val id: String

    fun arrange(site: SettlementSite, recipe: SettlementRecipe, random: Random): Layout
}

/** The layouts this build knows, by id. Unknown ids fall back to the organic one. */
class SettlementLayouts(layouts: List<SettlementLayout> = emptyList()) {

    private val byId = LinkedHashMap<String, SettlementLayout>().apply { layouts.forEach { put(it.id, it) } }

    val ids: Set<String> get() = byId.keys

    fun register(layout: SettlementLayout): SettlementLayouts = apply { byId[layout.id] = layout }

    fun layoutFor(id: String): SettlementLayout = byId[id] ?: byId.getValue(SettlementRecipe.ORGANIC)

    companion object {
        /** Shared, so a layout registered once is available to every world. */
        val standard: SettlementLayouts = SettlementLayouts(listOf(OrganicLayout, GridLayout, FortressLayout, CampLayout))
    }
}
