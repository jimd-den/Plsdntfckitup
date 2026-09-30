package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.combat.Attribute
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.Condition
import com.stratum.core.domain.combat.ConditionalModifier
import com.stratum.core.domain.combat.DamageSourceKind
import com.stratum.core.domain.combat.HitResolver
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.sandbox.BuildCode
import com.stratum.core.domain.sandbox.DummySpec
import com.stratum.core.domain.sandbox.ExplainedStat
import com.stratum.core.domain.sandbox.SourceKind
import com.stratum.core.domain.sandbox.StatQuery
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.WorldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxSessionTest {

    private val sandbox = WorldRules(sandbox = true)

    private val helm = ItemBase(
        "test:helm", "Helm", ItemSlot.HELM, defences = listOf(StatModifier(Stat.ARMOUR, ModifierKind.FLAT, 40f)),
        implicits = listOf(ModifierRange(Stat.RESISTANCE, ModifierKind.FLAT, 0.2f, 0.3f, "test:fire")),
    )
    private val ring = ItemBase("test:ring", "Ring", ItemSlot.RING, implicits = listOf(ModifierRange(Stat.CRIT_CHANCE, ModifierKind.FLAT, 0.05f, 0.1f)))
    private val left = UniqueDefinition(
        "test:left", "Left Hand", "test:ring", setId = "test:pair",
        modifiers = listOf(ModifierRange(Stat.DAMAGE, ModifierKind.INCREASED, 0.3f, 0.3f), ModifierRange(Stat.EVASION, ModifierKind.FLAT, 200f, 200f)),
    )
    private val right = UniqueDefinition(
        "test:right", "Right Hand", "test:ring", setId = "test:pair",
        modifiers = listOf(ModifierRange(Stat.MAX_HEALTH, ModifierKind.MORE, 0.2f, 0.2f), ModifierRange(Stat.BLOCK_CHANCE, ModifierKind.FLAT, 0.3f, 0.3f)),
    )
    private val pair = ItemSetDefinition(
        "test:pair", "The Pair",
        listOf(SetBonus(2, listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.5f), StatModifier(Stat.MAX_RESISTANCE, ModifierKind.FLAT, 0.05f, "test:fire")))),
    )
    private val oath = TraitDefinition(
        "test:oath", "Oath",
        modifiers = listOf(StatModifier(Stat.ATTACK_SPEED, ModifierKind.INCREASED, 0.25f)),
        conditional = listOf(
            ConditionalModifier(Condition.FullLife, StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.4f)),
            ConditionalModifier(Condition.Per(Attribute.LEVEL, 5), StatModifier(Stat.ARMOUR, ModifierKind.FLAT, 10f)),
        ),
    )
    private val ember = SupportDefinition("test:ember", "Ember", modifiers = listOf(StatModifier(Stat.SKILL_DAMAGE, ModifierKind.MORE, 0.3f)))

    private fun pack(classTraits: List<String> = listOf(oath.id), traits: List<TraitDefinition> = listOf(oath)) = CombatCoreFixtures.pack(traits = traits, classTraits = classTraits).let {
        it.copy(itemBases = listOf(helm, ring), uniques = listOf(left, right), itemSets = listOf(pair), supports = listOf(ember))
    }

    private fun session(rules: WorldRules = sandbox, pack: com.stratum.core.domain.content.ContentPack = pack()) = CombatCoreFixtures.session(pack, rules)

    private fun WorldSession.tools(): SandboxTools = assertNotNull(sandbox)

    /** A levelled hero with a spread of passives, a set, a rolled helm and a support: every kind of source at once. */
    private fun WorldSession.dressed(): WorldSession {
        val tools = tools()
        tools.setLevel(40)
        val tree = assertNotNull(passiveTree)
        var frontier = listOf(assertNotNull(passiveBuild).startId)
        repeat(4) {
            frontier = frontier.flatMap { tree.neighboursOf(it) }.distinct().filter { it !in player.passives }
            frontier.take(6).forEach { allocatePassive(it) }
        }
        listOf(
            ItemRequest(baseId = helm.id, itemLevel = 50, rarity = ItemRarity.RELIC),
            ItemRequest(uniqueId = left.id, itemLevel = 50),
            ItemRequest(uniqueId = right.id, itemLevel = 50),
        ).forEach { request -> equip((tools.spawnItem(request) as SandboxResult.ItemMade).item.instanceId) }
        tools.grantSupport(ember.id)
        linkSupport(CombatCoreFixtures.bolt.id, ember.id)
        // Gear raised the ceiling; a full-life condition should hold for the tests that read one.
        player = player.copy(health = inspector.combatStats(player).maxHealth)
        return this
    }

    @Test
    fun `only a sandbox world has sandbox tools`() {
        assertNull(session(WorldRules()).sandbox)
        assertNotNull(session().sandbox)
    }

    @Test
    fun `every explained number is the number combat uses`() {
        val session = session().dressed()
        val player = session.player
        val defender = session.inspector.defender(player)
        val stats = defender.stats
        val rules = session.rules.combat
        val expected = mapOf(
            StatQuery(ExplainedStat.DAMAGE) to stats.attackPower.toFloat(),
            StatQuery(ExplainedStat.LIFE) to stats.maxHealth.toFloat(),
            StatQuery(ExplainedStat.ARMOUR) to stats.armour.toFloat(),
            StatQuery(ExplainedStat.ATTACK_SPEED) to stats.attackSpeed,
            StatQuery(ExplainedStat.CRIT_CHANCE) to stats.critChance.coerceAtMost(rules.critChanceCap),
            StatQuery(ExplainedStat.CRIT_MULTIPLIER) to stats.critMultiplier,
            StatQuery(ExplainedStat.LIFE_STEAL) to stats.lifeSteal,
            StatQuery(ExplainedStat.RESISTANCE, damageTypeId = "test:fire") to HitResolver.cappedResistance("test:fire", defender, rules),
            StatQuery(ExplainedStat.EVASION) to defender.sheet.apply(Stat.EVASION, stats.evasion.toFloat()),
            StatQuery(ExplainedStat.BLOCK) to HitResolver.blockChance(defender, rules),
            StatQuery(ExplainedStat.MAX_RESOURCE) to player.resourceCeiling.toFloat(),
            StatQuery(ExplainedStat.MOVE_SPEED) to player.sheet(session::insertOrNull).multiplier(Stat.MOVE_SPEED),
            StatQuery(ExplainedStat.SKILL_POWER, skillId = CombatCoreFixtures.bolt.id) to assertNotNull(session.skillOrNull(CombatCoreFixtures.bolt.id)).powerMultiplier,
        )
        expected.forEach { (query, number) -> assertEquals(number, session.explain(query).value, "${query.stat}") }
        assertTrue(stats.attackPower > 0 && stats.evasion >= 0)
    }

    @Test
    fun `the breakdown names every kind of source that touched the number`() {
        val session = session().dressed()
        val damage = session.explain(StatQuery(ExplainedStat.DAMAGE))
        val kinds = damage.contributions.map { it.kind }.toSet() + damage.layers.first().base.map { it.kind }
        assertTrue(kinds.containsAll(listOf(SourceKind.CLASS, SourceKind.LEVEL, SourceKind.WEAPON, SourceKind.GEAR, SourceKind.SET_BONUS, SourceKind.CONDITIONAL)), kinds.toString())
        assertTrue(damage.contributions.any { it.label.startsWith("Oath: at full life") })

        val armour = session.explain(StatQuery(ExplainedStat.ARMOUR))
        assertTrue(armour.contributions.any { it.kind == SourceKind.CONDITIONAL && it.modifier.value == 80f }, "ten armour per five levels at level 40")

        val power = session.explain(StatQuery(ExplainedStat.SKILL_POWER, skillId = CombatCoreFixtures.bolt.id))
        assertEquals(listOf("Ember"), power.contributions.filter { it.kind == SourceKind.SUPPORT }.map { it.label })
    }

    @Test
    fun `crit chance under a cannot-crit keystone explains to nothing`() {
        val glass = TraitDefinition("test:glass", "Glass", keystones = setOf(Keystone.CANNOT_CRIT), modifiers = listOf(StatModifier(Stat.CRIT_CHANCE, ModifierKind.FLAT, 0.5f)))
        val session = session(pack = pack(classTraits = listOf(glass.id), traits = listOf(glass)))
        val crit = session.explain(StatQuery(ExplainedStat.CRIT_CHANCE))
        assertEquals(0f, crit.value)
        assertEquals("Cannot crit", crit.layers.last().title)
    }

    @Test
    fun `a dummy stands still, never swings, and the meter counts what hits it`() {
        val session = session()
        val tools = session.tools()
        val dummy = (tools.spawnDummy(DummySpec(life = DummySpec.HUGE_LIFE)) as SandboxResult.Spawned).enemies.single()
        val health = session.player.health
        repeat(6) {
            session.castSkill(CombatCoreFixtures.firebolt.id)
            repeat(10) {
                session.tick(0.05f)
                tools.advance(0.05f)
            }
        }
        val after = session.enemies.single { it.instanceId == dummy.instanceId }
        assertEquals(dummy.position, after.position)
        assertTrue(after.health < after.stats.maxHealth)
        assertEquals(health, session.player.health, "a dummy never fights back")

        val report = tools.meter.report()
        assertTrue(report.total > 0f)
        val kinds = report.bySource.map { it.source.kind }.toSet()
        assertTrue(DamageSourceKind.SKILL in kinds && DamageSourceKind.AILMENT in kinds, report.bySource.toString())
        assertEquals((after.stats.maxHealth - after.health).toFloat(), report.bySource.filter { it.source.kind == DamageSourceKind.SKILL }.sumOf { it.total.toDouble() }.toFloat() +
            report.bySource.filter { it.source.kind == DamageSourceKind.AILMENT }.sumOf { it.total.toDouble() }.toFloat(), 2f)
        tools.meter.reset()
        assertTrue(tools.meter.report().isEmpty)
    }

    @Test
    fun `any monster can be called, a boss at its own rank`() {
        val session = session()
        val tools = session.tools()
        val boss = (tools.spawnMonster(CombatCoreFixtures.overlord.id) as SandboxResult.Spawned).enemies.single()
        assertEquals(EnemyRank.BOSS, boss.rank)
        assertTrue(tools.isBoss(CombatCoreFixtures.overlord))
        assertEquals(SandboxResult.NotFound, tools.spawnMonster("nobody:here"))
    }

    @Test
    fun `items are conjured and rerolled in place, levels set, passives given back`() {
        val session = session()
        val tools = session.tools()
        val made = (tools.spawnItem(ItemRequest(baseId = helm.id, itemLevel = 60, rarity = ItemRarity.RARE)) as SandboxResult.ItemMade).item
        assertEquals(60, made.itemLevel)
        assertEquals(ItemRarity.RARE, made.rarity)
        session.equip(made.instanceId)
        val rerolled = tools.reroll(made.instanceId)
        assertIs<SandboxResult.Rerolled>(rerolled)
        assertEquals(made.instanceId, session.player.equipment[EquipmentSlot.HELM]?.instanceId)
        assertIs<SandboxResult.Refused>(tools.spawnItem(ItemRequest(baseId = helm.id, rarity = ItemRarity.UNIQUE)))

        tools.setLevel(30)
        assertEquals(30, session.player.level)
        val start = assertNotNull(session.passiveBuild).startId
        session.allocatePassive(session.passiveTree!!.neighboursOf(start).first())
        assertEquals(1, (tools.respec() as SandboxResult.Respecced).nodes)
        assertTrue(session.player.passives.isEmpty())

        tools.grantEverything()
        assertTrue(session.heldCurrency.isNotEmpty())
        assertEquals(1, session.player.supportCount(ember.id))
    }

    @Test
    fun `a build exported from one sandbox imports as the same gear`() {
        val session = session().dressed()
        val code = BuildCode.toCode(session.tools().exportBuild("Test"))
        val imported = session.tools().importBuild(code).getOrThrow()
        assertEquals(emptyList(), imported.skipped)
        assertEquals(session.player.equipment.modifiers(), imported.hero.equipment.modifiers())
        assertEquals(session.player.passives, imported.hero.passives)

        val again = CombatCoreFixtures.session(pack(), sandbox).let { fresh ->
            WorldSession(fresh.content, fresh.config, hero = imported.hero, terrainGenerator = CombatCoreFixtures.FlatTerrain)
        }
        assertEquals(session.playerStats, again.playerStats)
    }

    @Test
    fun `a life-paid skill can be cast with an empty resource pool`() {
        val blood = TraitDefinition("test:blood", "Blood", keystones = setOf(Keystone.LIFE_PAYS_COSTS))
        val session = session(pack = pack(classTraits = listOf(blood.id), traits = listOf(blood)))
        session.player = session.player.copy(resource = 0)
        val bolt = assertNotNull(session.skillOrNull(CombatCoreFixtures.bolt.id))
        val cost = session.costOf(bolt)
        assertEquals(0, cost.resource)
        assertTrue(cost.affordable(session.player.resource, session.player.health))
        val before = session.player.health
        assertTrue(session.castSkill(bolt.id) != AttackReport.NotEnoughResource)
        assertEquals(before - bolt.resourceCost, session.player.health)
    }

    @Test
    fun `the unbound caps are what the sandbox reports`() {
        assertTrue(session(sandbox.copy(combat = CombatRules.UNBOUND)).tools().capsLifted)
        assertTrue(!session().tools().capsLifted)
    }
}
