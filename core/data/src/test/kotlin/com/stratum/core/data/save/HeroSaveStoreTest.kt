package com.stratum.core.data.save

import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.difficulty.WaystoneMods
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.session.HeroSave
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeroSaveStoreTest {

    private val blade = ItemInstance(
        instanceId = "item_1", baseId = "igbo:machete", name = "Keen Machete of Embers", rarity = ItemRarity.RARE, itemLevel = 14,
        slot = EquipmentSlot.WEAPON, damageTypeId = "igbo:physical", minDamage = 11, maxDamage = 19, baseAttackSpeed = 1.3f,
        attackRange = 1, toolTier = 2, baseArmour = 0,
        affixes = listOf(AffixRoll("igbo:keen", "Keen", AffixKind.PREFIX, AffixStat.CRIT_CHANCE, 0.05f), AffixRoll("igbo:of_embers", "of Embers", AffixKind.SUFFIX, AffixStat.RESISTANCE, 0.2f, "igbo:fire")),
        sockets = SocketSet(3, listOf("igbo:bead", null, null)),
    )

    private val hero = HeroSave(
        id = "igbo:dibia", heroClassId = "igbo:dibia", level = 23, experience = 812, passives = setOf("a", "b"),
        equippedWeapon = blade, bag = listOf(blade.copy(instanceId = "item_2", sockets = SocketSet.NONE)),
        insertBag = mapOf("igbo:bead" to 2), currency = mapOf("stratum:currency/reforge" to 7),
        supportBag = mapOf("stratum:support/brutality" to 1), supports = mapOf("igbo:bolt" to listOf("stratum:support/swiftcast")),
        waystones = listOf(Waystone("waystone_1", tier = 4, mods = WaystoneMods.standard.take(2))), highestTier = 3, savedAt = 1_700_000_000_000,
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
}
