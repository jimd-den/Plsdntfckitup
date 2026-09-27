package com.stratum.content.igbo

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IgboGearTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
    private val generator = ItemGenerator(content.itemCatalogue)

    @Test
    fun `every kind of gear has a base to drop on`() {
        ItemSlot.entries.forEach { slot ->
            assertTrue(content.itemCatalogue.bases.any { it.slot == slot }, "nothing is worn as $slot")
            assertNotNull(generator.roll(itemLevel = 20, random = Random(slot.ordinal.toLong()), slot = slot), "no $slot rolled")
        }
    }

    @Test
    fun `the regalia is a six piece set with a bonus at two, four and six`() {
        val set = content.itemSet("igbo:ozo_regalia")!!
        assertEquals(6, content.itemCatalogue.piecesOf(set.id).size)
        assertEquals(listOf(2, 4, 6), set.bonuses.map { it.pieces })
    }

    @Test
    fun `every unique breaks a rule, and drops as itself`() {
        content.uniques.filter { it.setId == null }.forEach { unique ->
            assertTrue(unique.flags.isNotEmpty(), "${unique.name} changes no rule")
            val item = generator.unique(unique, itemLevel = 20, random = Random(1))!!
            assertEquals(ItemRarity.UNIQUE, item.rarity)
            assertEquals(unique.name, item.name)
            assertEquals(unique.flags, item.flags)
        }
    }

    @Test
    fun `rare finds take their names from the pack's words`() {
        val words = content.itemNames.flatMap { it.first + it.second }.toSet()
        val rare = generator.craft(content.itemBase("igbo:bronze_ring")!!, 20, ItemRarity.RARE, Random(3))
        assertTrue(rare.name.split(" ").all { it in words }, "named '${rare.name}'")
    }

    @Test
    fun `the bases grow into the pack's own ladder`() {
        val grown = content.itemBase("igbo:hide_shield~3")
        assertEquals("Lost-Wax Hide Shield", grown?.name)
    }
}
