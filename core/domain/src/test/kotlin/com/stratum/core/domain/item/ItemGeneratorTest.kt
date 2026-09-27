package com.stratum.core.domain.item

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ItemGeneratorTest {

    private val sword = ItemBase("t:sword", "Sword", ItemSlot.WEAPON, tags = setOf("blade"), weapon = WeaponProfile("t:phys", 10, 14))
    private val helm = ItemBase("t:helm", "Helm", ItemSlot.HELM, defences = listOf(StatModifier(Stat.ARMOUR, ModifierKind.FLAT, 20f)))
    private val ring = ItemBase("t:ring", "Ring", ItemSlot.RING, implicits = listOf(ModifierRange(Stat.MAX_HEALTH, ModifierKind.FLAT, 5f, 10f)))

    private fun flat(stat: Stat, min: Float, max: Float) = ModifierRange(stat, ModifierKind.FLAT, min, max)

    private val life = AffixDefinition(
        "t:life", "Hale", AffixKind.PREFIX,
        listOf(AffixTier(listOf(flat(Stat.MAX_HEALTH, 5f, 10f))), AffixTier(listOf(flat(Stat.MAX_HEALTH, 30f, 40f)), minItemLevel = 30, name = "Mighty")),
        group = "t:health",
    )
    private val moreLife = AffixDefinition.single("t:more_life", "Stout", AffixKind.PREFIX, flat(Stat.MAX_HEALTH, 1f, 2f), group = "t:health")
    private val bladeOnly = AffixDefinition.single("t:edge", "Edged", AffixKind.PREFIX, flat(Stat.DAMAGE, 1f, 3f), tags = setOf("blade"))
    private val plating = AffixDefinition.single(
        "t:plating", "Plated", AffixKind.PREFIX, ModifierRange(Stat.ARMOUR, ModifierKind.INCREASED, 1f, 1f), slots = setOf(ItemSlot.HELM), local = true,
    )
    private val suffixes = (1..4).map { AffixDefinition.single("t:s$it", "of $it", AffixKind.SUFFIX, flat(Stat.CRIT_CHANCE, 0.01f, 0.02f)) }

    private val catalogue = ItemCatalogue.of(
        listOf(sword, helm, ring), listOf(life, moreLife, bladeOnly, plating) + suffixes,
        uniques = listOf(
            UniqueDefinition("t:bloodring", "Bloodring", "t:ring", listOf(flat(Stat.LIFE_STEAL, 0.1f, 0.2f)), flags = setOf(BuildFlag.LIFE_STEAL_UNCAPPED)),
            UniqueDefinition("t:left", "Left", "t:ring", setId = "t:pair"),
            UniqueDefinition("t:right", "Right", "t:ring", setId = "t:pair"),
        ),
        sets = listOf(ItemSetDefinition("t:pair", "Pair", listOf(SetBonus(2, listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.5f)), setOf(BuildFlag.CANNOT_CRIT))))),
        namePools = listOf(ItemNamePool("t:names", listOf("Grim"), listOf("Band"), slots = setOf(ItemSlot.RING))),
    )
    private val generator = ItemGenerator(catalogue)

    @Test
    fun `the same seed makes the same item`() {
        assertEquals(generator.roll(25, Random(9)), generator.roll(25, Random(9)))
    }

    @Test
    fun `drops cover every kind of gear the pack defines`() {
        val slots = (1..300).mapNotNull { generator.roll(10, Random(it.toLong())) }.map { it.slot }.toSet()
        assertEquals(setOf(ItemSlot.WEAPON, ItemSlot.HELM, ItemSlot.RING), slots)
    }

    @Test
    fun `an item never carries two affixes of one group`() {
        repeat(50) { seed ->
            val item = generator.craft(ring, 40, ItemRarity.RELIC, Random(seed.toLong()))
            assertEquals(item.affixes.size, item.affixes.map { it.group }.distinct().size, item.affixes.toString())
        }
    }

    @Test
    fun `affixes only roll where their slots and tags allow`() {
        repeat(50) { seed ->
            val onRing = generator.craft(ring, 40, ItemRarity.RELIC, Random(seed.toLong()))
            assertTrue(onRing.affixes.none { it.definitionId == bladeOnly.id || it.definitionId == plating.id })
        }
        val onSword = (0..50).flatMap { generator.craft(sword, 40, ItemRarity.RELIC, Random(it.toLong())).affixes }
        assertTrue(onSword.any { it.definitionId == bladeOnly.id }, "a blade affix never rolled on a blade")
    }

    @Test
    fun `deep tiers only roll deep`() {
        val shallow = (0..60).flatMap { generator.craft(ring, 5, ItemRarity.RELIC, Random(it.toLong())).affixes }
        assertTrue(shallow.none { it.definitionId == life.id && it.tier == 2 })
        val deep = (0..60).flatMap { generator.craft(ring, 40, ItemRarity.RELIC, Random(it.toLong())).affixes }
        val mighty = deep.first { it.definitionId == life.id && it.tier == 2 }
        assertEquals("Mighty", mighty.name)
        assertTrue(mighty.modifiers.single().value >= 30f)
    }

    @Test
    fun `prefixes and suffixes share a rare evenly when both can roll`() {
        repeat(30) { seed ->
            val item = generator.craft(sword, 40, ItemRarity.RARE, Random(seed.toLong()))
            assertEquals(2, item.affixes.count { it.kind == AffixKind.PREFIX }, item.affixes.toString())
        }
    }

    @Test
    fun `local affixes change the item's own numbers`() {
        val plated = generator.craft(helm, 1, ItemRarity.COMMON, Random(1)).let { it.copy(affixes = listOf(plating.roll(1) { 0f })) }
        assertEquals(40, plated.armour, "100% increased armour did not double the helm's own 20")
        assertTrue(plated.modifiers().none { it.kind == ModifierKind.INCREASED && it.stat == Stat.ARMOUR }, "a local modifier reached the wearer")
    }

    @Test
    fun `rare items take a name from the pack's words, others read their affixes`() {
        assertEquals("Grim Band", generator.craft(ring, 10, ItemRarity.RARE, Random(2)).name)
        val magic = generator.craft(sword, 10, ItemRarity.UNCOMMON, Random(2))
        assertTrue(magic.name.contains("Sword"))
        // No pool names swords, so a rare sword falls back to its affixes.
        assertTrue(generator.craft(sword, 10, ItemRarity.RARE, Random(2)).name.contains("Sword"))
    }

    @Test
    fun `uniques drop as themselves with their flags`() {
        val unique = generator.unique(catalogue.unique("t:bloodring")!!, 10, Random(4))!!
        assertEquals(ItemRarity.UNIQUE, unique.rarity)
        assertEquals("Bloodring", unique.name)
        assertEquals(setOf(BuildFlag.LIFE_STEAL_UNCAPPED), unique.flags)
        assertTrue(unique.isFixed)
        val found = (1..3000).mapNotNull { generator.roll(10, Random(it.toLong()), slot = ItemSlot.RING) }.count { it.uniqueId != null }
        assertTrue(found in 1..300, "uniques dropped $found times in 3000 ring drops")
    }

    @Test
    fun `set pieces unlock their bonus only together`() {
        val left = generator.unique(catalogue.unique("t:left")!!, 10, Random(1))!!
        val right = generator.unique(catalogue.unique("t:right")!!, 10, Random(2))!!
        assertEquals(ItemRarity.SET, left.rarity)

        val one = Equipment.EMPTY.equipping(left)!!.equipment
        assertTrue(one.setBonuses.isEmpty())
        val both = one.equipping(right)!!.equipment
        assertEquals(EquipmentSlot.RING_RIGHT, both.slotOf(right.instanceId))
        assertTrue(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.5f) in both.modifiers())
        assertEquals(setOf(BuildFlag.CANNOT_CRIT), both.flags)
    }

    @Test
    fun `a pack with few bases grows deeper ones`() {
        val grown = catalogue.base("t:helm~4")
        assertNotNull(grown)
        assertEquals("Helm IV", grown.name)
        assertEquals(37, grown.minItemLevel)
        assertTrue(grown.defence(Stat.ARMOUR) > helm.defence(Stat.ARMOUR) * BaseFamilies.scale(helm, 37), "a rung is not better than its root at its depth")
        val deepHelms = (1..100).mapNotNull { generator.roll(50, Random(it.toLong()), slot = ItemSlot.HELM) }.map { it.baseId }.toSet()
        assertEquals(setOf("t:helm~3", "t:helm~4"), deepHelms, "only the deepest two rungs of a family drop")
    }

    @Test
    fun `tempering a unique rerolls within its own ranges`() {
        val unique = generator.unique(catalogue.unique("t:bloodring")!!, 10, Random(4))!!
        val tempered = generator.rerolled(unique.affixes.single(), Random(99))
        val range = catalogue.unique("t:bloodring")!!.modifiers.single()
        assertTrue(range.admits(tempered.modifiers.single()))
    }

    @Test
    fun `a rarity floor strikes the tiers below it and keeps the rest in proportion`() {
        val rolls = (1..400).map { generator.rollRarity(Random(it.toLong()), floor = ItemRarity.RARE) }
        assertTrue(rolls.all { it >= ItemRarity.RARE }, rolls.toString())
        assertTrue(rolls.count { it == ItemRarity.RARE } > rolls.count { it == ItemRarity.EPIC }, "rare stays commoner than epic")
    }

    @Test
    fun `a floored roll never rolls a lesser item`() {
        val items = (1..100).mapNotNull { generator.roll(20, Random(it.toLong()), floor = ItemRarity.EPIC) }
        assertTrue(items.all { it.rarity >= ItemRarity.EPIC || !it.rarity.isRolled })
    }
}
