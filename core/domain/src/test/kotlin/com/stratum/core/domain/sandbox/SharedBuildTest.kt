package com.stratum.core.domain.sandbox

import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.Equipment
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemCatalogue
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.item.WeaponProfile
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedBuildTest {

    private val sword = ItemBase("t:sword", "Sword", ItemSlot.WEAPON, weapon = WeaponProfile("t:phys", 10, 14))
    private val ring = ItemBase("t:ring", "Ring", ItemSlot.RING, implicits = listOf(ModifierRange(Stat.MAX_HEALTH, ModifierKind.FLAT, 5f, 10f)))
    private val keen = AffixDefinition.single("t:keen", "Keen", AffixKind.PREFIX, ModifierRange(Stat.DAMAGE, ModifierKind.INCREASED, 0.1f, 0.3f))
    private val catalogue = ItemCatalogue.of(
        listOf(sword, ring), listOf(keen),
        uniques = listOf(
            UniqueDefinition(
                "t:left", "Left", "t:ring", listOf(ModifierRange(Stat.LIFE_STEAL, ModifierKind.FLAT, 0.1f, 0.2f)),
                setId = "t:pair", flags = setOf(BuildFlag.CANNOT_CRIT),
            ),
        ),
        sets = listOf(ItemSetDefinition("t:pair", "Pair", listOf(SetBonus(2, listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.5f)))))),
    )
    private val generator = ItemGenerator(catalogue)

    private fun player(): PlayerState {
        val blade = generator.craft(sword, 30, ItemRarity.RARE, Random(4))
        val left = generator.unique(catalogue.unique("t:left")!!, 20, Random(5))!!
        return PlayerState(
            heroClassId = "t:warrior", position = WorldPoint(0f, 0f, 0f), level = 42,
            passives = setOf("t:a", "t:b"),
            equipment = Equipment.EMPTY.with(EquipmentSlot.WEAPON, blade).with(EquipmentSlot.RING_LEFT, left),
            supports = mapOf("t:fireball" to listOf("t:ember")),
        )
    }

    @Test
    fun `a build survives the code and comes back as the same hero`() {
        val player = player()
        val code = BuildCode.toCode(BuildCode.of(player, name = "Glass"))
        assertTrue(code.startsWith(BuildCode.PREFIX))
        val read = BuildCode.read(code).getOrThrow()
        assertEquals("Glass", read.name)

        val imported = BuildCode.toHero(read, catalogue, Random(99))
        assertEquals(emptyList(), imported.skipped)
        val hero = imported.hero
        assertEquals(42, hero.level)
        assertEquals(player.passives, hero.passives)
        assertEquals(player.supports, hero.supports)
        EquipmentSlot.entries.forEach { slot ->
            val before = player.equipment[slot]
            val after = hero.equipment[slot]
            assertEquals(before?.modifiers(), after?.modifiers(), "$slot")
            assertEquals(before?.name, after?.name)
            assertEquals(before?.rarity, after?.rarity)
            assertEquals(before?.averageDamage, after?.averageDamage)
            assertEquals(before?.flags, after?.flags)
        }
        assertEquals(player.equipment.modifiers(), hero.equipment.modifiers())
    }

    @Test
    fun `plain JSON reads too, and unknown items are named rather than guessed`() {
        val json = BuildCode.toJson(BuildCode.of(player()))
        val elsewhere = ItemCatalogue.of(listOf(ring))
        val imported = BuildCode.toHero(BuildCode.read(json).getOrThrow(), elsewhere, Random(1))
        assertEquals(2, imported.skipped.size)
        assertTrue(imported.hero.equipment.isEmpty)
    }

    @Test
    fun `something that is not a build is refused with a reason`() {
        assertTrue(BuildCode.read("{\"format\":\"stratum.plugin\",\"heroClassId\":\"x\"}").isFailure)
        assertTrue(BuildCode.read("hello").isFailure)
        assertTrue(BuildCode.read(BuildCode.PREFIX + "%%%").isFailure)
    }
}
