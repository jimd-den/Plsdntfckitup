package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.crafting.CurrencyEffect
import com.stratum.core.domain.crafting.StandardCrafting
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.difficulty.WaystoneMods
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.world.WorldConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun session(difficulty: Difficulty = Difficulty.BASE, hero: HeroSave? = null, seed: Long = 21L) =
    WorldSession(TestContent.assembled, WorldConfig(seed = seed, simulationRadius = 1), difficulty = difficulty, hero = hero)

private fun currencyFor(effect: CurrencyEffect) = StandardCrafting.currencies.first { it.effect == effect }.id

class CraftingSessionTest {

    private fun holding(effect: CurrencyEffect, count: Int = 5) = session().apply {
        player = player.withCurrency(currencyFor(effect), count)
    }

    @Test
    fun `a pack with combat gets the standard crafting set`() {
        assertEquals(StandardCrafting.currencies, TestContent.assembled.currencies)
        assertEquals(StandardCrafting.supports, TestContent.assembled.supports)
    }

    @Test
    fun `imbuing makes a common weapon rare, and spends one`() {
        val session = holding(CurrencyEffect.IMBUE)
        val weapon = session.player.equippedWeapon!!
        assertEquals(ItemRarity.COMMON, weapon.rarity)

        val crafted = assertIs<CraftResult.Crafted>(session.craft(weapon.instanceId, currencyFor(CurrencyEffect.IMBUE)))

        assertEquals(ItemRarity.RARE, crafted.after.rarity)
        assertEquals(ItemRarity.RARE.affixCount, crafted.after.affixes.size)
        assertEquals(crafted.after, session.player.equippedWeapon, "the item in hand is the one that changed")
        assertEquals(4, session.player.currencyCount(currencyFor(CurrencyEffect.IMBUE)))
    }

    @Test
    fun `currency that would do nothing is not spent`() {
        val session = holding(CurrencyEffect.REFORGE)
        val weapon = session.player.equippedWeapon!!

        assertIs<CraftResult.NoEffect>(session.craft(weapon.instanceId, currencyFor(CurrencyEffect.REFORGE)))
        assertEquals(5, session.player.currencyCount(currencyFor(CurrencyEffect.REFORGE)))
        assertEquals(CraftResult.NoneHeld, session().craft(weapon.instanceId, currencyFor(CurrencyEffect.REFORGE)))
    }

    @Test
    fun `ascending adds affixes and sockets, and a relic cannot ascend`() {
        val session = holding(CurrencyEffect.ASCEND, count = 10)
        val weapon = session.player.equippedWeapon!!

        repeat(ItemRarity.entries.size - 1) { session.craft(weapon.instanceId, currencyFor(CurrencyEffect.ASCEND)) }

        val relic = session.player.equippedWeapon!!
        assertEquals(ItemRarity.RELIC, relic.rarity)
        assertEquals(relic.affixes.size, relic.affixes.distinctBy { it.definitionId }.size, "never the same affix twice")
        assertTrue(relic.socketCount >= 3)
        assertIs<CraftResult.NoEffect>(session.craft(weapon.instanceId, currencyFor(CurrencyEffect.ASCEND)))
    }

    @Test
    fun `socketing grows sockets up to the limit and keeps what is slotted`() {
        val session = holding(CurrencyEffect.SOCKET, count = 10)
        val weapon = session.player.equippedWeapon!!
        repeat(StandardCrafting.MAX_SOCKETS + 2) { session.craft(weapon.instanceId, currencyFor(CurrencyEffect.SOCKET)) }

        assertEquals(StandardCrafting.MAX_SOCKETS, session.player.equippedWeapon!!.socketCount)
        assertEquals(10 - StandardCrafting.MAX_SOCKETS, session.player.currencyCount(currencyFor(CurrencyEffect.SOCKET)))
    }

    @Test
    fun `scouring strips a crafted item back to plain`() {
        val session = holding(CurrencyEffect.IMBUE).apply { player = player.withCurrency(currencyFor(CurrencyEffect.SCOUR)) }
        val weapon = session.player.equippedWeapon!!
        session.craft(weapon.instanceId, currencyFor(CurrencyEffect.IMBUE))

        val scoured = assertIs<CraftResult.Crafted>(session.craft(weapon.instanceId, currencyFor(CurrencyEffect.SCOUR))).after

        assertEquals(ItemRarity.COMMON, scoured.rarity)
        assertEquals(emptyList(), scoured.affixes)
    }
}

class SupportSessionTest {

    private val brutality = StandardCrafting.supports.first { it.name == "Brutality" }

    @Test
    fun `a linked support changes the skill as it is cast, and comes back out intact`() {
        val session = session().apply { player = player.withSupport(brutality.id) }
        val plain = session.skills.first()

        assertIs<SupportResult.Linked>(session.linkSupport(plain.id, brutality.id))
        val supported = session.skills.first()
        assertEquals(plain.powerMultiplier * 1.35f, supported.powerMultiplier, 0.0001f)
        assertEquals((plain.resourceCost * 1.3f).toInt(), supported.resourceCost)
        assertEquals(0, session.player.supportCount(brutality.id))

        assertIs<SupportResult.Unlinked>(session.unlinkSupport(plain.id, brutality.id))
        assertEquals(plain, session.skills.first())
        assertEquals(1, session.player.supportCount(brutality.id))
    }

    @Test
    fun `a skill holds three supports, each once`() {
        val session = session().apply {
            player = StandardCrafting.supports.fold(player) { held, support -> held.withSupport(support.id, 2) }
        }
        val skill = session.skills.first().id

        StandardCrafting.supports.take(3).forEach { assertIs<SupportResult.Linked>(session.linkSupport(skill, it.id)) }
        assertEquals(SupportResult.AlreadyLinked, session.linkSupport(skill, StandardCrafting.supports.first().id))
        assertEquals(SupportResult.SkillFull, session.linkSupport(skill, StandardCrafting.supports.last().id))
        assertEquals(SupportResult.UnknownSkill, session.linkSupport("test:nothing", brutality.id))
    }

    @Test
    fun `a conversion support turns the skill's damage into its element`() {
        val embers = SupportDefinition("test:embers", "Embers", convertsToDamageTypeId = TestContent.fire.id)
        val content = ContentPackAssembler().assemble(listOf(TestContent.pack.copy(supports = listOf(embers))))
        val session = WorldSession(content, WorldConfig(seed = 3L, simulationRadius = 1)).apply { player = player.withSupport(embers.id) }

        session.linkSupport(TestContent.strike.id, embers.id)

        assertEquals(TestContent.fire.id, session.skillOrNull(TestContent.strike.id)!!.damageTypeId)
    }
}

class DifficultySessionTest {

    @Test
    fun `each tier makes monsters tougher and pays better`() {
        val base = session()
        val hard = session(Difficulty(tier = 3))

        val calm = base.spawn(TestContent.rat, base.player.position)
        val fierce = hard.spawn(TestContent.rat, hard.player.position)
        val rankScale = { enemy: com.stratum.core.domain.actor.EnemyInstance -> enemy.rank.healthMultiplier }

        assertTrue(fierce.stats.maxHealth / rankScale(fierce) > calm.stats.maxHealth / rankScale(calm) * 2f)
        assertEquals(1f + Difficulty.RARITY_PER_TIER * 3, hard.difficulty.rewards.multiplier(Stat.ITEM_RARITY))
    }

    @Test
    fun `a waystone's mods reach its monsters`() {
        val hulking = WaystoneMods.standard.first { it.name == "Hulking" }
        val plain = session(Difficulty(tier = 1))
        val swollen = session(Difficulty(tier = 1, mods = listOf(hulking)))

        val a = plain.spawn(TestContent.rat, plain.player.position)
        val b = swollen.spawn(TestContent.rat, swollen.player.position)
        assertEquals(a.stats.maxHealth / a.rank.healthMultiplier * 1.4f, b.stats.maxHealth / b.rank.healthMultiplier, 2f)
    }

    @Test
    fun `felling a champion at the hardest tier reached opens the next`() {
        val session = session(Difficulty(tier = 0))
        val champion = session.spawn(TestContent.rat, session.player.position).copy(rank = EnemyRank.CHAMPION, health = 1)
        session.enemies = listOf(champion)

        repeat(40) {
            session.attack()
            session.tick(0.1f)
        }

        assertEquals(1, session.player.highestTier)
    }

    @Test
    fun `waystones roll more mods the higher they go`() {
        val random = Random(1)
        assertEquals(1, WaystoneMods.roll(1, WaystoneMods.standard, random).mods.size)
        assertEquals(4, WaystoneMods.roll(12, WaystoneMods.standard, random).mods.size)
    }
}

class HeroSaveSessionTest {

    @Test
    fun `a hero carries level, passives, gear and pouch into a new world`() {
        val first = session(seed = 1L).apply {
            player = player.copy(level = 12, experience = 40).withCurrency(currencyFor(CurrencyEffect.IMBUE), 3)
        }
        val start = first.passiveBuild!!
        val node = start.tree.neighboursOf(start.startId).first()
        first.allocatePassive(node)
        first.craft(first.player.equippedWeapon!!.instanceId, currencyFor(CurrencyEffect.IMBUE))
        val saved = first.heroSave(savedAt = 99L)

        val next = session(seed = 2L, hero = saved)

        assertEquals(12, next.player.level)
        assertEquals(40, next.player.experience)
        assertEquals(setOf(node), next.player.passives)
        assertEquals(first.player.equippedWeapon, next.player.equippedWeapon)
        assertEquals(2, next.player.currencyCount(currencyFor(CurrencyEffect.IMBUE)))
        assertEquals(first.playerStats.maxHealth, next.playerStats.maxHealth, "the build is re-resolved on arrival")
        assertEquals(next.player.maxHealthWithGear, next.player.health, "and the hero arrives rested")
        assertNotEquals(first.config.seed, next.config.seed, "in a different world")
    }

    @Test
    fun `a saved passive survives only while the tree still has it`() {
        val saved = HeroSave(id = "x", heroClassId = TestContent.digger.id, level = 5, passives = setOf("no-such-node"))

        assertEquals(emptySet(), session(hero = saved).player.passives)
    }
}
