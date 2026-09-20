package com.stratum.engine.world

import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.ItemRarity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LootRollerTest {

    private val roller = LootRoller(TestContent.weapons, TestContent.affixes)

    @Test
    fun `the same seed rolls the same item`() {
        val a = roller.roll(itemLevel = 10, random = Random(42))
        val b = roller.roll(itemLevel = 10, random = Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `different seeds roll different items`() {
        val items = (1..40).map { roller.roll(itemLevel = 10, random = Random(it.toLong())) }
        assertTrue(items.map { it?.name }.distinct().size > 1, "every seed produced the same item")
    }

    @Test
    fun `a base above the item level never rolls`() {
        // The greatsword is gated to item level 15; everything else is open.
        val rolled = (1..60).mapNotNull { roller.roll(itemLevel = 1, random = Random(it.toLong())) }
        assertTrue(rolled.isNotEmpty())
        assertTrue(
            rolled.none { it.baseId == TestContent.greatsword.id },
            "a level 15 base rolled at item level 1",
        )

        // And it does appear once the level allows it, or the gate would be
        // indistinguishable from the base simply never rolling.
        val deep = (1..200).mapNotNull { roller.roll(itemLevel = 30, random = Random(it.toLong())) }
        assertTrue(deep.any { it.baseId == TestContent.greatsword.id }, "the gated base never became available")
    }

    @Test
    fun `nothing rolls when no base is eligible`() {
        val highOnly = LootRoller(listOf(TestContent.greatsword), TestContent.affixes)
        assertNull(highOnly.roll(itemLevel = 1, random = Random(1)))
    }

    @Test
    fun `rarity determines how many affixes an item carries`() {
        ItemRarity.entries.forEach { rarity ->
            val item = roller.craft(TestContent.club, itemLevel = 20, rarity = rarity, random = Random(7))
            assertEquals(
                rarity.affixCount,
                item.affixes.size,
                "${rarity.name} rolled ${item.affixes.size} affixes",
            )
        }
    }

    @Test
    fun `an item never rolls the same affix twice`() {
        repeat(40) { seed ->
            val item = roller.craft(TestContent.club, itemLevel = 30, rarity = ItemRarity.RELIC, random = Random(seed.toLong()))
            assertEquals(
                item.affixes.size,
                item.affixes.map { it.definitionId }.distinct().size,
                "duplicate affix in ${item.name}",
            )
        }
    }

    @Test
    fun `affixes gated by item level do not appear on low level drops`() {
        repeat(60) { seed ->
            val item = roller.craft(TestContent.club, itemLevel = 1, rarity = ItemRarity.RELIC, random = Random(seed.toLong()))
            assertTrue(
                item.affixes.none { it.definitionId == TestContent.lateAffix.id },
                "a level-gated affix rolled at item level 1",
            )
        }
    }

    @Test
    fun `common is overwhelmingly the most frequent tier`() {
        val random = Random(99)
        val counts = (1..4000).map { roller.rollRarity(random) }.groupingBy { it }.eachCount()
        val common = counts[ItemRarity.COMMON] ?: 0
        val relic = counts[ItemRarity.RELIC] ?: 0
        assertTrue(common > 2000, "common was only $common of 4000")
        assertTrue(relic < 100, "relic dropped $relic times in 4000, which is not rare")
    }

    @Test
    fun `a rarity bonus of one guarantees better than common`() {
        val random = Random(5)
        repeat(200) {
            assertTrue(roller.rollRarity(random, rarityBonus = 1f) != ItemRarity.COMMON)
        }
    }

    @Test
    fun `base damage scales with item level`() {
        val low = roller.craft(TestContent.club, itemLevel = 1, rarity = ItemRarity.COMMON, random = Random(3))
        val high = roller.craft(TestContent.club, itemLevel = 40, rarity = ItemRarity.COMMON, random = Random(3))
        assertTrue(high.averageDamage > low.averageDamage, "item level did not improve the weapon")
    }

    @Test
    fun `an item name reads prefix base suffix`() {
        val item = roller.craft(TestContent.club, itemLevel = 30, rarity = ItemRarity.RARE, random = Random(11))
        val prefix = item.affixes.firstOrNull { it.kind == AffixKind.PREFIX }
        val suffix = item.affixes.firstOrNull { it.kind == AffixKind.SUFFIX }
        if (prefix != null) assertTrue(item.name.startsWith(prefix.name), "name was '${item.name}'")
        if (suffix != null) assertTrue(item.name.endsWith(suffix.name), "name was '${item.name}'")
        assertTrue(item.name.contains(TestContent.club.name))
    }

    @Test
    fun `affix values stay inside their declared range`() {
        repeat(100) { seed ->
            val item = roller.craft(TestContent.club, itemLevel = 40, rarity = ItemRarity.RELIC, random = Random(seed.toLong()))
            item.affixes.forEach { roll ->
                val definition = TestContent.affixes.first { it.id == roll.definitionId }
                assertTrue(
                    roll.value >= definition.minValue && roll.value <= definition.maxValue,
                    "${roll.name} rolled ${roll.value}, outside ${definition.minValue}..${definition.maxValue}",
                )
            }
        }
    }

    @Test
    fun `affixes become the stats the item grants`() {
        val item = roller.craft(TestContent.club, itemLevel = 20, rarity = ItemRarity.RARE, random = Random(4))
        val stats = item.toStats()

        val expectedAttack = item.averageDamage +
            item.affixes.filter { it.stat == AffixStat.ATTACK_POWER }.sumOf { it.value.toDouble() }.toInt()
        assertEquals(expectedAttack, stats.attackPower)

        item.affixes.filter { it.stat == AffixStat.RESISTANCE }.forEach { affix ->
            val type = affix.damageTypeId!!
            assertTrue(stats.resistanceTo(type) > 0f, "resistance affix produced no resistance")
        }
    }

    @Test
    fun `mining speed affixes are reported separately from combat stats`() {
        val item = roller.craft(TestContent.pick, itemLevel = 20, rarity = ItemRarity.RARE, random = Random(8))
        // Mining speed must not silently become attack power.
        val mining = item.affixes.filter { it.stat == AffixStat.MINING_SPEED }
        assertEquals(
            mining.sumOf { it.value.toDouble() }.toFloat(),
            item.miningSpeedBonus,
            absoluteTolerance = 1e-4f,
        )
    }

    @Test
    fun `a crafted item keeps the identity of the base it was made from`() {
        val item = roller.craft(TestContent.pick, itemLevel = 5, rarity = ItemRarity.COMMON, random = Random(1))
        assertEquals(TestContent.pick.id, item.baseId)
        assertEquals(TestContent.pick.damageTypeId, item.damageTypeId)
        assertEquals(TestContent.pick.toolTier, item.toolTier)
        assertNotNull(item.instanceId)
    }
}
