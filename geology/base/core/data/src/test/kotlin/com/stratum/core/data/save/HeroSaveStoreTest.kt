package com.stratum.core.data.save

import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.difficulty.WaystoneMods
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.Equipment
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroSaveStoreTest {

    private val blade = ItemInstance(
        instanceId = "item_1", baseId = "igbo:machete", name = "Keen Machete of Embers", rarity = ItemRarity.RARE, itemLevel = 14,
        slot = ItemSlot.WEAPON, damageTypeId = "igbo:physical", minDamage = 11, maxDamage = 19, baseAttackSpeed = 1.3f,
        attackRange = 1, toolTier = 2,
        affixes = listOf(
            AffixRoll("igbo:keen", "Keen", AffixKind.PREFIX, listOf(AffixStat.CRIT_CHANCE.modifier(0.05f)), tier = 2),
            AffixRoll("igbo:of_embers", "of Embers", AffixKind.SUFFIX, listOf(AffixStat.RESISTANCE.modifier(0.2f, "igbo:fire")), group = "resist"),
        ),
        sockets = SocketSet(3, listOf("igbo:bead", null, null)),
    )

    private val ring = ItemInstance(
        instanceId = "item_3", baseId = "igbo:cowrie_ring", name = "Oath of the Market", rarity = ItemRarity.SET, itemLevel = 20,
        slot = ItemSlot.RING, defences = listOf(StatModifier(Stat.RESISTANCE, ModifierKind.FLAT, 0.1f, "igbo:fire")),
        affixes = listOf(AffixRoll("igbo:oath", "Oath of the Market", AffixKind.UNIQUE, listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.2f)))),
        implicits = listOf(StatModifier(Stat.MAX_HEALTH, ModifierKind.FLAT, 12f)), baseName = "Cowrie Ring", tags = setOf("ring", "jewellery"),
        requiredLevel = 18, uniqueId = "igbo:oath", setId = "igbo:market", flags = setOf(BuildFlag.CANNOT_CRIT), flavour = "Paid in full.",
        setBonuses = listOf(SetBonus(2, listOf(StatModifier(Stat.ITEM_RARITY, ModifierKind.INCREASED, 0.3f)), setOf(BuildFlag.SKILLS_COST_HEALTH))),
    )

    private val hero = HeroSave(
        id = "igbo:dibia", heroClassId = "igbo:dibia", level = 23, experience = 812, passives = setOf("a", "b"),
        equipment = Equipment.EMPTY.with(EquipmentSlot.WEAPON, blade).with(EquipmentSlot.RING_RIGHT, ring),
        bag = listOf(blade.copy(instanceId = "item_2", sockets = SocketSet.NONE)),
        insertBag = mapOf("igbo:bead" to 2), currency = mapOf("stratum:currency/reforge" to 7),
        supportBag = mapOf("stratum:support/brutality" to 1), supports = mapOf("igbo:bolt" to listOf("stratum:support/swiftcast")),
        waystones = listOf(Waystone("waystone_1", tier = 4, mods = WaystoneMods.standard.take(2))), highestTier = 3, reputation = mapOf("igbo:nri" to 240), savedAt = 1_700_000_000_000,
    )

    private fun store() = FileHeroSaveStore(Files.createTempDirectory("heroes").toFile())

    @Test
    fun `a hero survives the trip to disk unchanged`() {
        assertEquals(hero, HeroSaveJson.decode(HeroSaveJson.encode(hero)))

        val store = store()
        store.save(hero)
        assertEquals(hero, store.load(hero.id))
    }

    @Test
    fun `every equipment slot is kept, not only the weapon`() {
        val loaded = HeroSaveJson.decode(HeroSaveJson.encode(hero))
        assertEquals(ring, loaded.equipment[EquipmentSlot.RING_RIGHT])
        assertEquals(blade, loaded.equippedWeapon)
    }

    @Test
    fun `saving again replaces the hero, and the roster is newest first`() {
        val store = store()
        store.save(hero)
        store.save(hero.copy(level = 24, savedAt = hero.savedAt + 1))
        store.save(hero.copy(id = "igbo:smith", heroClassId = "igbo:smith", savedAt = 5))

        assertEquals(listOf(24, 23), store.all().map { it.level })
        store.delete("igbo:smith")
        assertEquals(1, store.all().size)
    }

    @Test
    fun `a corrupt save is skipped, not fatal`() {
        val directory = Files.createTempDirectory("heroes").toFile()
        val store = FileHeroSaveStore(directory)
        store.save(hero)
        File(directory, "broken.hero").writeText("{ not json")
        File(directory, "odd.hero").writeText("""{ "id": "x", "heroClassId": "y", "weapon": { "instanceId": "i", "baseId": "b", "name": "n", "rarity": "MYTHIC", "itemLevel": 1, "slot": "WEAPON", "damageTypeId": "d", "minDamage": 1, "maxDamage": 2, "attackSpeed": 1, "attackRange": 1, "toolTier": 1, "armour": 0 } }""")

        assertEquals(listOf(hero.id), store.all().map { it.id })
        assertNull(store.load("broken"))
    }

    @Test
    fun `a save from before equipment slots still reads, weapon and old affixes intact`() {
        val old = """
            { "version": 1, "id": "igbo:smith", "heroClassId": "igbo:smith", "level": 7,
              "weapon": { "instanceId": "i1", "baseId": "igbo:mma_nkwu", "name": "Keen Mma Nkwu", "rarity": "UNCOMMON", "itemLevel": 6,
                "slot": "WEAPON", "damageTypeId": "igbo:physical", "minDamage": 11, "maxDamage": 17, "attackSpeed": 1.6, "attackRange": 1,
                "toolTier": 1, "armour": 3, "glyph": "🔪",
                "affixes": [ { "id": "igbo:keen", "name": "Keen", "kind": "PREFIX", "stat": "CRIT_CHANCE", "value": 0.05 },
                             { "id": "igbo:of_ash", "name": "of Ash", "kind": "SUFFIX", "stat": "RESISTANCE", "value": 0.2, "damageTypeId": "igbo:solar" } ],
                "sockets": [ null ] },
              "bag": [ { "instanceId": "i2", "baseId": "igbo:old", "name": "Old Charm", "rarity": "COMMON", "itemLevel": 1, "slot": "CHARM",
                "damageTypeId": "igbo:physical", "minDamage": 1, "maxDamage": 2, "attackSpeed": 1, "attackRange": 1, "toolTier": 1, "armour": 0 } ] }
        """.trimIndent()

        val hero = HeroSaveJson.decode(old)

        val weapon = assertNotNull(hero.equippedWeapon)
        assertEquals("Keen Mma Nkwu", weapon.name)
        assertEquals(3, weapon.armour)
        assertEquals(StatModifier(Stat.CRIT_CHANCE, ModifierKind.FLAT, 0.05f), weapon.affixes[0].modifiers.single())
        assertEquals(StatModifier(Stat.RESISTANCE, ModifierKind.FLAT, 0.2f, "igbo:solar"), weapon.affixes[1].modifiers.single())
        assertEquals(1, weapon.socketCount)
        assertEquals(ItemSlot.AMULET, hero.bag.single().slot, "the old charm slot reads as the neck")

        // Written back out, it is a current save that reads the same.
        assertEquals(hero, HeroSaveJson.decode(HeroSaveJson.encode(hero)))
        assertTrue("\"weapon\"" !in HeroSaveJson.encode(hero), "the legacy field is still being written")
    }

    @Test
    fun `gear saved in a slot it no longer fits is moved, not lost`() {
        val misplaced = """
            { "id": "x", "heroClassId": "y",
              "equipment": { "HELM": { "instanceId": "r", "baseId": "b", "name": "Ring", "rarity": "COMMON", "itemLevel": 1, "slot": "RING" } } }
        """.trimIndent()

        val hero = HeroSaveJson.decode(misplaced)

        assertEquals("r", hero.equipment[EquipmentSlot.RING_LEFT]?.instanceId)
    }
}
