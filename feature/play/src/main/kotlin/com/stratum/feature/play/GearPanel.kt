package com.stratum.feature.play

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.item.Equipment
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.session.PlayerState
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a tooltip line is coloured: what kind of thing it says. */
enum class LineTone { BASE, IMPLICIT, AFFIX, UNIQUE, INSERT, FLAG, SET_HEADER, SET_ACTIVE, SET_WAITING, FLAVOUR, REQUIREMENT }

data class TooltipLine(val text: String, val tone: LineTone)

/** A set as a tooltip needs it: its name and how many distinct pieces it has. */
data class SetInfo(val name: String, val pieces: Int)

/**
 * An item, read the way the tooltip reads it: its base line, then what it
 * rolled, what is slotted in it, the rules it breaks, its set and how far
 * along the set is, and the line in italics.
 *
 * Built from the item model's own words -- [ItemInstance.baseLine], each
 * modifier's describe, the flag's label -- so the panel can never phrase a
 * number differently from the rest of the game.
 */
object ItemTooltip {

    fun lines(
        item: ItemInstance,
        worn: Equipment,
        inserts: (String) -> InsertDefinition?,
        setInfo: (String) -> SetInfo?,
        canWear: Boolean = true,
    ): List<TooltipLine> = buildList {
        add(TooltipLine("${item.baseLine} · item level ${item.itemLevel}", LineTone.BASE))
        item.implicits.forEach { add(TooltipLine(it.describe(), LineTone.IMPLICIT)) }
        item.affixes.forEach { roll ->
            val tone = if (item.isFixed) LineTone.UNIQUE else LineTone.AFFIX
            roll.modifiers.forEach { add(TooltipLine(it.describe() + if (roll.local) " (this item)" else "", tone)) }
        }
        item.sockets.filled.forEach { id ->
            val insert = id?.let(inserts)
            add(TooltipLine(if (insert == null) "◌ Empty socket" else "${insert.glyph} ${insert.name}: ${insert.statLine}", LineTone.INSERT))
        }
        item.flags.forEach { add(TooltipLine(it.label, LineTone.FLAG)) }
        item.setId?.let { setId ->
            val info = setInfo(setId)
            val wearing = worn.setPieces[setId] ?: 0
            add(TooltipLine("${info?.name ?: setId} · $wearing/${info?.pieces ?: item.setBonuses.maxOfOrNull { it.pieces } ?: 0} worn", LineTone.SET_HEADER))
            item.setBonuses.sortedBy { it.pieces }.forEach { bonus ->
                add(TooltipLine("(${bonus.pieces}) ${bonus.description}", if (bonus.pieces <= wearing) LineTone.SET_ACTIVE else LineTone.SET_WAITING))
            }
        }
        if (item.flavour.isNotBlank()) add(TooltipLine(item.flavour, LineTone.FLAVOUR))
        if (!canWear) add(TooltipLine("Needs level ${item.requiredLevel}", LineTone.REQUIREMENT))
    }
}

/** One number that would move: what it is now, and what it would be. */
data class StatDelta(val label: String, val before: Float, val after: Float, val percent: Boolean = false) {
    val change: Float get() = after - before

    val isGain: Boolean get() = change > 0f

    /** "+12 life", "-3% crit". */
    val text: String
        get() {
            val amount = if (percent) "${(abs(change) * 100).roundToInt()}%" else if (abs(change) < 10f && change % 1f != 0f) "%.2f".format(abs(change)) else abs(change).roundToInt().toString()
            return "${if (change >= 0f) "+" else "-"}$amount $label"
        }
}

/**
 * The whole character with a piece swapped in, against the character now.
 *
 * Whole, not the item's own lines: a ring's "increased damage" shows as the
 * damage it really adds to this build, a set piece shows its bonus switching
 * on, and a two-hander shows the shield it takes off. The caller supplies
 * [statsOf] -- the world's own resolution of a player's fought-with numbers --
 * so the comparison sees traits and keystones exactly as the fight does.
 */
object GearComparison {

    /** How the character would change wearing [item] in [slot], or wherever it goes. Only the numbers that move. */
    fun deltas(
        player: PlayerState,
        item: ItemInstance,
        slot: EquipmentSlot?,
        statsOf: (PlayerState) -> CombatStats,
        damageTypeNames: Map<String, String> = emptyMap(),
    ): List<StatDelta> {
        val wearing = player.collecting(item).equipping(item, slot)
        if (wearing == player.collecting(item)) return emptyList()
        return between(statsOf(player), statsOf(wearing), damageTypeNames)
    }

    fun between(before: CombatStats, after: CombatStats, damageTypeNames: Map<String, String> = emptyMap()): List<StatDelta> {
        val resistances = (before.resistances.keys + after.resistances.keys).sorted().map { type ->
            StatDelta("${damageTypeNames[type] ?: type.substringAfter(':')} res", before.rawResistanceTo(type), after.rawResistanceTo(type), percent = true)
        }
        return (
            listOf(
                StatDelta("attack", before.attackPower.toFloat(), after.attackPower.toFloat()),
                StatDelta("speed", before.attackSpeed, after.attackSpeed),
                StatDelta("crit", before.critChance, after.critChance, percent = true),
                StatDelta("crit multi", before.critMultiplier, after.critMultiplier, percent = true),
                StatDelta("life", before.maxHealth.toFloat(), after.maxHealth.toFloat()),
                StatDelta("armour", before.armour.toFloat(), after.armour.toFloat()),
                StatDelta("life steal", before.lifeSteal, after.lifeSteal, percent = true),
            ) + resistances
            ).filter { abs(it.change) > EPSILON }
    }

    private const val EPSILON = 1e-4f
}

/** One piece in the panel: the item, its tooltip, and what it would change. */
data class GearCard(
    val item: ItemInstance,
    val lines: List<TooltipLine>,
    /** Against what is worn now; empty for something already worn. */
    val deltas: List<StatDelta> = emptyList(),
    val canWear: Boolean = true,
)

/**
 * The gear panel: ten places to wear things, what is in each, and the bag
 * sorted against the place the player is looking at.
 */
data class GearPanelState(
    val selectedSlot: EquipmentSlot? = null,
    val worn: Map<EquipmentSlot, GearCard> = emptyMap(),
    /** Bag items that go in [selectedSlot], or the whole bag when none is chosen, each compared against wearing it. */
    val candidates: List<GearCard> = emptyList(),
    /** A bag item the player tapped to read in full. */
    val inspected: GearCard? = null,
    /** Every set being worn, with its progress, for the summary line. */
    val sets: List<SetProgress> = emptyList(),
)

data class SetProgress(val name: String, val worn: Int, val pieces: Int, val nextBonusAt: Int?)

/** Builds [GearPanelState] from the player: the pure part of the satchel, so it can be tested without a screen. */
object GearPanelBuilder {

    fun build(
        player: PlayerState,
        selectedSlot: EquipmentSlot?,
        inspectedId: String?,
        inserts: (String) -> InsertDefinition?,
        setInfo: (String) -> SetInfo?,
        statsOf: (PlayerState) -> CombatStats,
        damageTypeNames: Map<String, String> = emptyMap(),
    ): GearPanelState {
        val worn = player.equipment
        val cards = worn.items.mapValues { (_, item) -> GearCard(item, ItemTooltip.lines(item, worn, inserts, setInfo)) }
        val bag = player.bag.filter { selectedSlot == null || selectedSlot in it.slot.fits }
        val candidates = bag.map { item ->
            val canWear = player.canWear(item)
            GearCard(
                item,
                ItemTooltip.lines(item, worn, inserts, setInfo, canWear),
                if (canWear) GearComparison.deltas(player, item, selectedSlot, statsOf, damageTypeNames) else emptyList(),
                canWear,
            )
        }
        val sets = worn.setPieces.map { (setId, count) ->
            val info = setInfo(setId)
            val bonuses = worn.all.first { it.setId == setId }.setBonuses
            SetProgress(info?.name ?: setId, count, info?.pieces ?: bonuses.maxOfOrNull { it.pieces } ?: count, bonuses.map { it.pieces }.filter { it > count }.minOrNull())
        }
        return GearPanelState(
            selectedSlot = selectedSlot,
            worn = cards,
            candidates = candidates,
            inspected = inspectedId?.let { id -> candidates.firstOrNull { it.item.instanceId == id } ?: cards.values.firstOrNull { it.item.instanceId == id } },
            sets = sets,
        )
    }

    /**
     * The paper doll, as rows of three: head and neck at the top, hands and
     * body in the middle, rings either side of the waist, feet at the bottom.
     * Null is an empty cell of the grid, not a slot.
     */
    val doll: List<List<EquipmentSlot?>> = listOf(
        listOf(null, EquipmentSlot.HELM, EquipmentSlot.AMULET),
        listOf(EquipmentSlot.WEAPON, EquipmentSlot.CHEST, EquipmentSlot.OFFHAND),
        listOf(EquipmentSlot.RING_LEFT, EquipmentSlot.BELT, EquipmentSlot.RING_RIGHT),
        listOf(EquipmentSlot.GLOVES, null, EquipmentSlot.BOOTS),
    )
}
