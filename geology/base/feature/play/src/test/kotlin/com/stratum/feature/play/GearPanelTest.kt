package com.stratum.feature.play

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.item.Equipment
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemCatalogue
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.item.WeaponProfile
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.WorldPoint
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GearPanelTest {

    private val sword = ItemBase("t:sword", "Sword", ItemSlot.WEAPON, weapon = WeaponProfile("t:phys", 10, 14))
    private val ring = ItemBase("t:ring", "Ring", ItemSlot.RING)
    private val catalogue = ItemCatalogue.of(
        listOf(sword, ring),
        uniques = listOf(
            UniqueDefinition("t:left", "Left", "t:ring", listOf(ModifierRange(Stat.DAMAGE, ModifierKind.INCREASED, 0.2f, 0.2f)), setId = "t:pair", flavour = "Two of a kind.", flags = setOf(BuildFlag.CANNOT_CRIT)),
            UniqueDefinition("t:right", "Right", "t:ring", listOf(ModifierRange(Stat.MAX_HEALTH, ModifierKind.FLAT, 30f, 30f)), setId = "t:pair"),
        ),
        sets = listOf(ItemSetDefinition("t:pair", "The Pair", listOf(SetBonus(2, listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.5f)))))),
    )
    private val generator = ItemGenerator(catalogue)
    private val left = generator.unique(catalogue.unique("t:left")!!, 10, Random(1))!!
    private val right = generator.unique(catalogue.unique("t:right")!!, 10, Random(2))!!
    private val blade = generator.craft(sword, 10, ItemRarity.COMMON, Random(3))
    private val setInfo: (String) -> SetInfo? = { id -> catalogue.set(id)?.let { SetInfo(it.name, catalogue.piecesOf(id).size) } }
    private val ember = InsertDefinition("t:ember", "Ember", modifiers = listOf(StatModifier(Stat.DAMAGE, ModifierKind.FLAT, 2f)))

    private fun player(equipment: Equipment = Equipment.EMPTY, bag: List<com.stratum.core.domain.item.ItemInstance> = emptyList()) =
        PlayerState("t:hero", WorldPoint(0f, 0f, 0f), equipment = equipment, bag = bag)

    private val statsOf: (PlayerState) -> CombatStats = { it.combatStatsWith({ null }) }

    @Test
    fun `a set piece reads its set's progress, its flags and its flavour`() {
        val wearingOne = Equipment.EMPTY.with(EquipmentSlot.RING_LEFT, left)
        val lines = ItemTooltip.lines(left, wearingOne, { null }, setInfo)
        val texts = lines.map { it.text }
        assertTrue("The Pair · 1/2 worn" in texts, texts.toString())
        assertEquals(LineTone.SET_WAITING, lines.single { it.text.startsWith("(2)") }.tone)
        assertTrue(BuildFlag.CANNOT_CRIT.label in texts)
        assertEquals(LineTone.FLAVOUR, lines.last().tone)

        val both = wearingOne.with(EquipmentSlot.RING_RIGHT, right)
        assertEquals(LineTone.SET_ACTIVE, ItemTooltip.lines(left, both, { null }, setInfo).single { it.text.startsWith("(2)") }.tone)
    }

    @Test
    fun `sockets read what is in them, and an empty one says so`() {
        val socketed = blade.copy(sockets = SocketSet(2, listOf("t:ember", null)))
        val texts = ItemTooltip.lines(socketed, Equipment.EMPTY, { if (it == ember.id) ember else null }, setInfo).map { it.text }
        assertTrue(texts.any { it.contains("Ember") })
        assertTrue("◌ Empty socket" in texts)
        assertTrue(texts.first().startsWith(blade.baseLine))
    }

    @Test
    fun `the comparison is the whole character, so a set bonus shows when the second piece goes on`() {
        val wearing = player(Equipment.EMPTY.with(EquipmentSlot.WEAPON, blade).with(EquipmentSlot.RING_LEFT, left), bag = listOf(right))
        val deltas = GearComparison.deltas(wearing, right, EquipmentSlot.RING_RIGHT, statsOf)
        val attack = deltas.single { it.label == "attack" }
        assertTrue(attack.isGain, "the pair's more damage switches on")
        assertEquals(30f, deltas.single { it.label == "life" }.change)
    }

    @Test
    fun `an item that cannot go in the chosen place changes nothing`() {
        val wearing = player(bag = listOf(blade))
        assertTrue(GearComparison.deltas(wearing, blade, EquipmentSlot.HELM, statsOf).isEmpty())
    }

    @Test
    fun `choosing a place on the doll sorts the bag to what goes there`() {
        val wearing = player(Equipment.EMPTY.with(EquipmentSlot.RING_LEFT, left), bag = listOf(right, blade))
        val rings = GearPanelBuilder.build(wearing, EquipmentSlot.RING_RIGHT, null, { null }, setInfo, statsOf)
        assertEquals(listOf(right.instanceId), rings.candidates.map { it.item.instanceId })
        assertEquals(listOf("The Pair"), rings.sets.map { it.name })
        assertEquals(2, rings.sets.single().nextBonusAt)

        val everything = GearPanelBuilder.build(wearing, null, blade.instanceId, { null }, setInfo, statsOf)
        assertEquals(2, everything.candidates.size)
        assertEquals(blade.instanceId, everything.inspected?.item?.instanceId)
    }

    @Test
    fun `the doll has a place for each of the ten slots, once`() {
        assertEquals(EquipmentSlot.entries.toSet(), GearPanelBuilder.doll.flatten().filterNotNull().toSet())
        assertEquals(EquipmentSlot.entries.size, GearPanelBuilder.doll.flatten().filterNotNull().size)
    }
}
