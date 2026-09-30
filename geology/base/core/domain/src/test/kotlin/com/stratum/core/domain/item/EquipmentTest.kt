package com.stratum.core.domain.item

import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EquipmentTest {

    private fun item(id: String, slot: ItemSlot, twoHanded: Boolean = false, level: Int = 1, vararg modifiers: StatModifier) = ItemInstance(
        instanceId = id, baseId = "t:$id", name = id, rarity = ItemRarity.COMMON, itemLevel = 1, slot = slot,
        damageTypeId = if (slot == ItemSlot.WEAPON) "t:phys" else null, minDamage = 10, maxDamage = 10, baseAttackSpeed = 1f,
        twoHanded = twoHanded, requiredLevel = level, implicits = modifiers.toList(),
    )

    private val sword = item("sword", ItemSlot.WEAPON)
    private val maul = item("maul", ItemSlot.WEAPON, twoHanded = true)
    private val shield = item("shield", ItemSlot.OFFHAND)

    @Test
    fun `rings fill the left hand, then the right, then replace the left`() {
        val a = item("a", ItemSlot.RING)
        val b = item("b", ItemSlot.RING)
        val c = item("c", ItemSlot.RING)
        val one = Equipment.EMPTY.equipping(a)!!.equipment
        val two = one.equipping(b)!!.equipment
        assertEquals(EquipmentSlot.RING_RIGHT, two.slotOf("b"))
        val three = two.equipping(c)!!
        assertEquals(listOf(a), three.removed)
        assertEquals(EquipmentSlot.RING_LEFT, three.equipment.slotOf("c"))
    }

    @Test
    fun `a two handed weapon takes the off hand with it, and a shield takes it back off`() {
        val armed = Equipment.EMPTY.equipping(sword)!!.equipment.equipping(shield)!!.equipment
        val swapped = armed.equipping(maul)!!
        assertEquals(setOf(sword, shield), swapped.removed.toSet())
        assertNull(swapped.equipment[EquipmentSlot.OFFHAND])

        val back = swapped.equipment.equipping(shield)!!
        assertEquals(listOf(maul), back.removed)
        assertNull(back.equipment.weapon)
    }

    @Test
    fun `gear cannot go where it does not fit`() {
        assertNull(Equipment.EMPTY.equipping(shield, EquipmentSlot.HELM))
    }

    @Test
    fun `a player equips into the bag and back, losing nothing`() {
        val player = PlayerState("t:hero", WorldPoint(0f, 0f, 0f)).equipping(sword).equipping(shield)
        val swapped = player.collecting(maul).equipping(maul)
        assertEquals(maul, swapped.equippedWeapon)
        assertEquals(setOf("sword", "shield"), swapped.bag.map { it.instanceId }.toSet())
        assertSame(swapped, swapped.equipping(shield, EquipmentSlot.HELM), "a refused equip changed the player")
    }

    @Test
    fun `worn gear feeds the one stat sheet`() {
        val band = item("band", ItemSlot.RING, modifiers = arrayOf(StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.5f)))
        val bare = PlayerState("t:hero", WorldPoint(0f, 0f, 0f)).equipping(sword)
        val ringed = bare.equipping(band)
        assertEquals(((bare.combatStats.attackPower) * 1.5f).toInt(), ringed.combatStats.attackPower)
    }

    @Test
    fun `a hero save keeps every slot and still takes a starting weapon when it held none`() {
        val band = item("band", ItemSlot.RING)
        val player = PlayerState("t:hero", WorldPoint(0f, 0f, 0f)).equipping(band)
        val save = HeroSave.of(player)
        val restored = save.restoreOnto(PlayerState("t:hero", WorldPoint(0f, 0f, 0f)).equipping(sword))
        assertEquals(band, restored.equipment[EquipmentSlot.RING_LEFT])
        assertEquals(sword, restored.equippedWeapon)
    }

    @Test
    fun `gear above the hero's level is never an automatic upgrade`() {
        val heavy = item("heavy", ItemSlot.HELM, level = 30)
        val player = PlayerState("t:hero", WorldPoint(0f, 0f, 0f))
        assertTrue(!player.isUpgrade(heavy))
        assertTrue(player.isUpgrade(item("light", ItemSlot.HELM)))
    }
}
