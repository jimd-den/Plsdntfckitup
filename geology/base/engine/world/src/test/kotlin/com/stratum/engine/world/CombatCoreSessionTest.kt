package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.WorldRules
import com.stratum.engine.world.CombatCoreFixtures.FLOOR
import com.stratum.engine.world.CombatCoreFixtures.enemy
import com.stratum.engine.world.CombatCoreFixtures.place
import com.stratum.engine.world.CombatCoreFixtures.run
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatCoreSessionTest {

    private val fixtures = CombatCoreFixtures

    // ---- projectiles ---------------------------------------------------------

    @Test
    fun `a projectile skill is released, flies, and lands a moment later`() {
        val session = fixtures.session()
        session.place(fixtures.dummy, dx = 5f)
        assertIs<AttackReport.Cast>(session.castSkill(fixtures.bolt.id))
        assertEquals(1, session.snapshot().projectiles.size, "the bolt is not in the world")
        assertEquals(1000, session.enemy("dummy")!!.health, "a projectile landed the instant it was thrown")

        session.run(1f)
        assertTrue(session.enemy("dummy")!!.health < 1000, "the bolt never arrived")
        assertTrue(session.snapshot().projectiles.isEmpty(), "a bolt that hit kept flying")
    }

    @Test
    fun `a wall stops a projectile`() {
        val session = fixtures.session()
        session.place(fixtures.dummy, dx = 5f)
        val px = floor(session.player.position.x).toInt()
        val py = floor(session.player.position.y).toInt()
        for (dy in -3..3) for (z in FLOOR + 1..FLOOR + 3) session.editableWorld.setBlock(BlockPos(px + 2, py + dy, z), TestContent.stone)
        session.castSkill(fixtures.bolt.id)
        session.run(1.5f)
        assertEquals(1000, session.enemy("dummy")!!.health, "the bolt went through stone")
    }

    @Test
    fun `a ranged monster shoots projectiles at the player`() {
        val session = fixtures.session()
        session.place(fixtures.archer, dx = 6f)
        val before = session.player.health
        val sawArrow = (1..40).any { session.tick(0.05f); session.snapshot().projectiles.any { it.side == CombatSide.MONSTERS } }
        assertTrue(sawArrow, "the archer never loosed")
        session.run(2f)
        assertTrue(session.player.health < before, "the arrow never hit")
    }

    // ---- telegraphs and the roll ---------------------------------------------

    @Test
    fun `a telegraphed slam marks the ground and lands when the wind-up ends`() {
        val session = fixtures.session()
        session.place(fixtures.brute, dx = 1f)
        session.tick(0.05f)
        val marker = session.snapshot().telegraphs.singleOrNull()
        assertNotNull(marker, "the wind-up was not marked on the ground")
        assertTrue(marker.hostile)
        val before = session.player.health
        session.run(1.2f)
        assertTrue(session.player.health < before, "standing in the marker was safe")
        assertTrue(session.snapshot().telegraphs.isEmpty())
    }

    @Test
    fun `rolling out of the marker avoids the slam`() {
        val session = fixtures.session()
        session.place(fixtures.brute, dx = 1f)
        session.tick(0.05f)
        assertTrue(session.snapshot().telegraphs.isNotEmpty())
        val before = session.player.health
        session.setMoveInput(-1f, 0f)
        session.run(0.3f)
        assertIs<DodgeResult.Rolling>(session.dodge())
        session.run(1.2f)
        assertEquals(before, session.player.health, "the roll did not carry the player clear")
    }

    // ---- statuses, flasks and kills --------------------------------------------

    @Test
    fun `an ailment burns on after the hit, and a burning kill fills the flasks`() {
        val session = fixtures.session()
        session.place(fixtures.dummy, dx = 4f)
        session.enemies = session.enemies.map { it.copy(health = 45) }
        session.castSkill(fixtures.firebolt.id)
        session.run(0.6f)
        assertTrue(session.statusesOf("dummy").has(fixtures.burn.id), "fire did not burn")
        assertEquals(20, session.flasks.single().charges)
        assertIs<FlaskResult.Drunk>(session.useFlask(0))
        assertEquals(10, session.flasks.single().charges)
        session.run(3f)
        assertNull(session.enemy("dummy"), "the burn did not finish it")
        assertEquals(15, session.flasks.single().charges, "a kill by burning gave no charges")
    }

    @Test
    fun `a flask restores life and cannot be drunk dry`() {
        val session = fixtures.session()
        session.player = session.player.copy(health = 10)
        assertIs<FlaskResult.Drunk>(session.useFlask(0))
        assertEquals(70, session.player.health)
        assertIs<FlaskResult.Drunk>(session.useFlask(0))
        assertIs<FlaskResult.Empty>(session.useFlask(0))
        assertIs<FlaskResult.NoSuchFlask>(session.useFlask(3))
    }

    @Test
    fun `a stun stops the player swinging until it wears off`() {
        val session = fixtures.session()
        session.place(fixtures.clubber, dx = 1f)
        session.run(0.2f)
        assertTrue(session.statusesOf(WorldSession.PLAYER_ACTOR_ID).has(fixtures.daze.id), "the club did not daze")
        assertEquals(AttackReport.Stunned, session.attack())
        session.enemies = emptyList()
        session.run(1.2f)
        assertTrue(session.attack() !is AttackReport.Stunned)
    }

    // ---- monsters helping monsters, and bosses ----------------------------------

    @Test
    fun `a support monster heals its hurt allies`() {
        val session = fixtures.session()
        session.place(fixtures.mender, dx = 8f)
        session.place(fixtures.dummy, dx = 9f)
        session.enemies = session.enemies.map { if (it.instanceId == "dummy") it.copy(health = 200) else it }
        session.run(0.3f)
        assertEquals(250, session.enemy("dummy")!!.health)
    }

    @Test
    fun `a boss enters its phases as it falls, calling adds and enraging`() {
        val session = fixtures.session()
        session.place(fixtures.overlord, dx = 8f)
        session.enemies = session.enemies.map { it.copy(health = 450) }
        val events = session.run(0.1f)
        assertTrue(events.any { it is CombatEvent.BossPhaseBegan && it.phaseName == "Summons" }, "no phase began")
        assertEquals(2, session.enemies.count { it.definitionId == fixtures.dummy.id }, "the adds did not come")
        assertEquals(1, session.enemy("overlord")!!.phase)

        val attack = session.enemy("overlord")!!.stats.attackPower
        session.enemies = session.enemies.map { if (it.instanceId == "overlord") it.copy(health = 200) else it }
        session.run(0.1f)
        val boss = session.enemy("overlord")!!
        assertEquals(2, boss.phase)
        assertEquals(attack * 2, boss.stats.attackPower, "the fury did not enrage")
        assertTrue(session.statusesOf("overlord").has(fixtures.rage.id))
    }

    // ---- keystones, leech and triggers ------------------------------------------

    @Test
    fun `life can pay for skills when a keystone says so`() {
        val bloodMagic = TraitDefinition("test:blood", "Blood", keystones = setOf(Keystone.LIFE_PAYS_COSTS))
        val session = fixtures.session(fixtures.pack(traits = listOf(bloodMagic), classTraits = listOf(bloodMagic.id)))
        val resource = session.player.resource
        val health = session.player.health
        session.castSkill(fixtures.bolt.id)
        assertEquals(resource, session.player.resource)
        assertEquals(health - fixtures.bolt.resourceCost, session.player.health)
    }

    @Test
    fun `leech trickles in at the world's rate unless it is instant`() {
        val vampire = CombatStats(maxHealth = 120, attackPower = 100, critChance = 0f, attackRange = 1, lifeSteal = 1f)
        val slow = fixtures.session(fixtures.pack(stats = vampire))
        slow.place(fixtures.dummy, dx = 1f)
        slow.player = slow.player.copy(health = 20)
        slow.attack()
        assertEquals(20, slow.player.health, "pooled leech landed at once")
        slow.enemies = emptyList()
        slow.run(1f)
        // Twenty percent of maximum life a second, from a pool far bigger than that.
        assertTrue(slow.player.health in 40..50, "leech recovered ${slow.player.health}")

        val instant = TraitDefinition("test:vamp", "Vamp", keystones = setOf(Keystone.INSTANT_LEECH))
        val quick = fixtures.session(fixtures.pack(traits = listOf(instant), classTraits = listOf(instant.id), stats = vampire))
        quick.place(fixtures.dummy, dx = 1f)
        quick.player = quick.player.copy(health = 20)
        quick.attack()
        assertTrue(quick.player.health > 100, "instant leech was not instant")
    }

    private fun echoing(rules: WorldRules, cooldown: Float): Int {
        val echo = TraitDefinition("test:echo", "Echo", triggers = listOf(TriggerDefinition(TriggerEvent.ON_HIT, cooldownSeconds = cooldown, castSkillId = fixtures.nova.id)))
        val session = fixtures.session(fixtures.pack(traits = listOf(echo), classTraits = listOf(echo.id)), rules)
        session.place(fixtures.dummy, dx = 1f, id = "a")
        session.place(fixtures.dummy, dx = -1f, id = "b")
        session.place(fixtures.dummy, dy = 2f, dx = 0f, id = "c")
        return assertIs<AttackReport.Landed>(session.attack()).hits.size
    }

    @Test
    fun `an on-hit trigger that casts a hitting skill is bounded by its cooldown`() {
        // The swing hits one; its trigger's nova hits all three; the trigger is then cooling.
        assertEquals(4, echoing(WorldRules(), cooldown = 0.25f))
    }

    @Test
    fun `with no cooldowns at all, depth and budget still end the loop`() {
        val depthOnly = echoing(WorldRules(combat = CombatRules(triggerCooldownFloor = 0f, triggerDepth = 3, triggerBudget = 256)), cooldown = 0f)
        // Depth 0 swing, then novas at depths 1, 2 and 3, each triggered by every hit of the one before: 1 + 3 + 9 + 27.
        assertEquals(40, depthOnly)
        val unbound = echoing(fixtures.unbound(), cooldown = 0f)
        assertTrue(unbound <= 1 + 3 * (CombatRules.MAX_TRIGGER_BUDGET + 1), "the budget did not hold: $unbound hits")
    }

    @Test
    fun `a skill that casts itself stops at the trigger depth`() {
        val recursive = fixtures.nova.copy(id = "test:recurse", effects = listOf(SkillEffect.Damage(TestContent.physical.id), SkillEffect.CastSkill("test:recurse")))
        val pack = fixtures.pack().let { it.copy(skills = it.skills + recursive, heroClasses = it.heroClasses.map { h -> h.copy(abilityIds = h.abilityIds + recursive.id) }) }
        val session = fixtures.session(pack)
        session.place(fixtures.dummy, dx = 1f)
        val hits = assertIs<AttackReport.Landed>(session.castSkill(recursive.id)).hits.size
        assertEquals(CombatRules().triggerDepth + 1, hits)
    }

    @Test
    fun `conditional damage against burning enemies applies only to burning ones`() {
        val arsonist = TraitDefinition(
            "test:arson", "Arson",
            conditional = listOf(com.stratum.core.domain.combat.ConditionalModifier(com.stratum.core.domain.combat.Condition.TargetHas("burning"), StatModifier(Stat.DAMAGE, ModifierKind.MORE, 1f))),
        )
        val session = fixtures.session(fixtures.pack(traits = listOf(arsonist), classTraits = listOf(arsonist.id)))
        session.place(fixtures.dummy, dx = 1f)
        val cold = assertIs<AttackReport.Landed>(session.attack()).totalDamage
        session.castSkill(fixtures.firebolt.id)
        session.run(1f)
        assertTrue(session.statusesOf("dummy").has(fixtures.burn.id))
        val hot = assertIs<AttackReport.Landed>(session.attack()).totalDamage
        assertTrue(hot >= cold * 2 - 2, "burning target took $hot against $cold")
    }

    @Test
    fun `the same seed and inputs fight the same fight`() {
        fun fight(): List<Int> {
            val session = fixtures.session(seed = 42L)
            session.place(fixtures.brute, dx = 1f)
            session.place(fixtures.archer, dx = 5f)
            val health = mutableListOf<Int>()
            repeat(60) {
                session.tick(0.05f)
                if (it % 10 == 0) session.attack()
                health += session.player.health
            }
            return health + session.enemies.map { it.health }
        }
        assertEquals(fight(), fight())
    }

    @Test
    fun `the instant resolver still ignores deliveries that land later`() {
        val outcome = CombatResolver().castSkill(
            CombatStats(attackPower = 10), com.stratum.core.domain.world.WorldPoint(0f, 0f, 5f), com.stratum.core.domain.world.Direction.EAST,
            emptyList(), fixtures.bolt, kotlin.random.Random(1),
        )
        assertIs<AttackOutcome.NoTarget>(outcome)
        assertFalse(fixtures.bolt.delivery == SkillDelivery.MELEE)
    }
}
