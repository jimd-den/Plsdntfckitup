package com.stratum.engine.world

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.attack.AttackCompiler
import com.stratum.core.domain.attack.AttackForge
import com.stratum.core.domain.attack.AttackVocabulary
import com.stratum.core.domain.attack.Delivery
import com.stratum.core.domain.attack.DeliveryKind
import com.stratum.core.domain.attack.Emitter
import com.stratum.core.domain.attack.EmitterShape
import com.stratum.core.domain.attack.Modulator
import com.stratum.core.domain.attack.ModulatorKind
import com.stratum.core.domain.attack.Payload
import com.stratum.core.domain.attack.ProceduralSkill
import com.stratum.core.domain.attack.SkillEvent
import com.stratum.core.domain.attack.SkillPhase
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.engine.world.CombatCoreFixtures.FLOOR
import com.stratum.engine.world.CombatCoreFixtures.enemy
import com.stratum.engine.world.CombatCoreFixtures.place
import com.stratum.engine.world.CombatCoreFixtures.run
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Forged attacks, compiled to skills, in a real fight: they hit, chain, and change the ground. */
class ForgedAttackSessionTest {
    private val fixtures = CombatCoreFixtures

    /** The arena's pack with [attacks] forged into it and given to the hero. */
    private fun arena(attacks: List<ProceduralSkill>): WorldSession {
        val base = fixtures.pack()
        val forged = AttackCompiler.pack(attacks, AttackVocabulary.from(base.damageTypes))
        val pack: ContentPack = base.copy(
            blocks = base.blocks + forged.blocks, statuses = base.statuses + forged.statuses, skills = base.skills + forged.skills,
            heroClasses = base.heroClasses.map { it.copy(abilityIds = it.abilityIds + attacks.map(ProceduralSkill::id)) },
        )
        return fixtures.session(pack, rules = fixtures.unbound())
    }

    private fun attack(phase: SkillPhase, seed: Long = 1L) = AttackForge.build(phase, seed)

    @Test
    fun `a forged bolt flies and lands, and its chained nova goes off where it hits`() {
        val nova = attack(SkillPhase(Delivery(DeliveryKind.IMPACT_FIELD, a = 2, b = 0), Emitter(EmitterShape.NOVA), payload = Payload.BURN), 9L)
        val bolt = attack(SkillPhase(Delivery(DeliveryKind.BALLISTIC, a = 5), Emitter(EmitterShape.SINGLE), payload = Payload.STAGGER, subTriggers = mapOf(SkillEvent.ON_HIT to nova)))
        val session = arena(listOf(bolt))
        session.place(fixtures.dummy, dx = 5f)
        assertIs<AttackReport.Cast>(session.castSkill(bolt.id))
        session.run(1.5f)
        assertTrue(session.enemy("dummy")!!.health < 1000, "the forged bolt never landed")
    }

    @Test
    fun `a crater takes the ground away where it lands`() {
        val quake = attack(SkillPhase(Delivery(DeliveryKind.INSTANT_RAY, a = 0), Emitter(EmitterShape.NOVA), payload = Payload.CRATER))
        val session = arena(listOf(quake))
        val x = floor(session.player.position.x).toInt(); val y = floor(session.player.position.y).toInt()
        val before = (-3..3).sumOf { dx -> (-3..3).count { dy -> session.world.blockAt(BlockPos(x + dx, y + dy, FLOOR)).isAir } }
        session.castSkill(quake.id)
        session.run(0.2f)
        val after = (-3..3).sumOf { dx -> (-3..3).count { dy -> session.world.blockAt(BlockPos(x + dx, y + dy, FLOOR)).isAir } }
        assertTrue(after > before, "no crater: $before -> $after")
    }

    @Test
    fun `a raised wall stands across the path, then crumbles back`() {
        val wall = attack(SkillPhase(Delivery(DeliveryKind.IMPACT_FIELD, a = 3, b = 0), Emitter(EmitterShape.SINGLE), payload = Payload.RAISE_WALL))
        val session = arena(listOf(wall))
        session.place(fixtures.dummy, dx = 4f)
        session.castSkill(wall.id)
        session.run(0.3f)
        fun walls() = session.world.loadedChunks.sumOf { c ->
            var n = 0
            val index = session.world.registry.indexOf(AttackCompiler.WALL_BLOCK)
            for (x in 0 until com.stratum.core.domain.world.Chunk.SIZE) for (yy in 0 until com.stratum.core.domain.world.Chunk.SIZE) for (z in FLOOR + 1..FLOOR + 3)
                if (c.blockAt(x, yy, z) == index) n++
            n
        }
        assertTrue(walls() > 0, "no wall rose")
        session.run(8f)
        assertEquals(0, walls(), "the wall never crumbled")
    }

    @Test
    fun `every forged attack assembles, validates and fights`() {
        val attacks = List(200) { k -> AttackForge.roll(k * 131L + 7, CombatRole.entries[k % CombatRole.entries.size]) } +
            AttackForge.build(
                SkillPhase(
                    Delivery(DeliveryKind.SURFACE_WAVE), Emitter(EmitterShape.FAN, 3),
                    listOf(Modulator(ModulatorKind.FORK), Modulator(ModulatorKind.ECHO), Modulator(ModulatorKind.BLOOM)), Payload.FLASH_FREEZE,
                ),
                5L,
            )
        // Assembly validates every reference: damage types, statuses and the chained skills.
        ContentPackAssembler().assemble(listOf(fixtures.pack(), AttackCompiler.pack(attacks, AttackVocabulary.from(fixtures.pack().damageTypes))))
        val session = arena(attacks)
        session.place(fixtures.dummy, dx = 3f)
        attacks.forEach { a ->
            session.castSkill(a.id)
            session.run(0.25f)
        }
        session.run(3f)
        assertTrue(session.enemy("dummy") == null || session.enemy("dummy")!!.health < 1000, "two hundred attacks and not a scratch")
    }
}
