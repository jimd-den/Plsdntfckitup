package com.stratum.core.domain.attack

import com.stratum.core.domain.actor.CombatRole
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttackGrammarTest {

    private val billion = BigInteger.valueOf(1_000_000_000L)

    @Test
    fun `there are billions of attacks and billions of looks`() {
        // Named choices alone, one phase: tens of millions; with one chained attack, far past billions.
        assertTrue(AttackForge.phases() > BigInteger.valueOf(10_000_000L), "phases: ${AttackForge.phases()}")
        assertTrue(AttackForge.attacks() > billion * BigInteger.valueOf(1_000_000L), "attacks: ${AttackForge.attacks()}")
        assertTrue(AttackForge.phases(steps = true) > billion, "stepped phases: ${AttackForge.phases(steps = true)}")
        assertTrue(AttackLook.space() > billion * BigInteger.valueOf(100L), "looks: ${AttackLook.space()}")
    }

    @Test
    fun `random attacks look different from one another`() {
        val n = 200_000
        val looks = HashSet<Long>(n * 2); val mechanics = HashSet<String>(n * 2)
        for (k in 0 until n) {
            val skill = AttackForge.roll(k * 7919L + 3)
            looks += skill.look.signature
            mechanics += AttackCode.encode(skill.copy(seed = 0))
        }
        assertTrue(looks.size > n * 0.999, "distinct looks: ${looks.size} of $n")
        assertTrue(mechanics.size > n * 0.99, "distinct mechanics: ${mechanics.size} of $n")
    }

    @Test
    fun `the same mechanics in a different style look different, and mechanics show through`() {
        val phase = SkillPhase(Delivery(DeliveryKind.BALLISTIC), Emitter(EmitterShape.FAN, 3), listOf(Modulator(ModulatorKind.FORK)), Payload.BURN)
        val a = AttackForge.build(phase, 1L).look; val b = AttackForge.build(phase, 2L).look
        assertNotEquals(a.signature, b.signature)
        // A frost attack never wears a ballistic body on a ray, nor a fire family's colours.
        val ray = AttackForge.build(phase.copy(delivery = Delivery(DeliveryKind.INSTANT_RAY), element = Element.FROST), 1L).look
        assertEquals(DeliveryKind.INSTANT_RAY, ray.body.delivery)
        assertTrue(ray.primary != a.primary)
    }

    @Test
    fun `an attack's code carries it exactly`() {
        repeat(500) { k ->
            val skill = AttackForge.roll(k * 31L + 9, CombatRole.entries[k % CombatRole.entries.size])
            val code = AttackCode.encode(skill)
            val back = assertNotNull(AttackCode.decode(code), code)
            assertEquals(skill, back)
            assertEquals(code, AttackCode.encode(back))
        }
        assertEquals(null, AttackCode.decode("ak1:!!"))
        assertEquals(null, AttackCode.decode("sm1:abc"))
    }

    @Test
    fun `more power costs more and recharges slower`() {
        val plain = SkillPhase(Delivery(DeliveryKind.BALLISTIC), Emitter(EmitterShape.SINGLE), emptyList(), Payload.STAGGER)
        val forked = plain.copy(modulators = listOf(Modulator(ModulatorKind.FORK, 6), Modulator(ModulatorKind.ECHO, 6)))
        val chained = forked.copy(subTriggers = mapOf(SkillEvent.ON_CRIT to AttackForge.build(plain.copy(geometry = Emitter(EmitterShape.NOVA)), 4L)))
        val a = AttackForge.build(plain, 1L); val b = AttackForge.build(forked, 1L); val c = AttackForge.build(chained, 1L)
        assertTrue(a.energyCost < b.energyCost && b.energyCost < c.energyCost, "${a.energyCost} ${b.energyCost} ${c.energyCost}")
        assertTrue(a.cooldownSeconds <= b.cooldownSeconds && b.cooldownSeconds <= c.cooldownSeconds)
        assertTrue(c.cooldownSeconds <= 30f)
    }

    @Test
    fun `rolls suit the role, stay within the guards, and every attack is named and described`() {
        val ranged = List(400) { AttackForge.roll(it.toLong(), CombatRole.RANGED) }
        val reach = setOf(DeliveryKind.BALLISTIC, DeliveryKind.INSTANT_RAY, DeliveryKind.TETHER, DeliveryKind.SURFACE_WAVE)
        assertTrue(ranged.all { it.lead.delivery.kind in reach })
        val brutes = List(400) { AttackForge.roll(it.toLong() + 10_000, CombatRole.BRUTE) }
        assertTrue(brutes.count { it.lead.payload.family != PayloadFamily.AFFLICTION } > 350)
        for (s in ranged + brutes) {
            assertTrue(s.depth <= AttackGrammar.MAX_DEPTH)
            assertTrue(s.name.isNotBlank() && s.id.startsWith("forge:"))
            assertTrue(AttackNaming.describe(s).size >= 2)
        }
        assertTrue(ranged.any { it.lead.subTriggers.isNotEmpty() }, "some attacks chain others")
    }

    @Test
    fun `the forge composes what is slotted, and mutation changes one thing`() {
        val made = AttackForge.compose(
            ForgePiece.Core(Delivery(DeliveryKind.SURFACE_WAVE)),
            listOf(ForgePiece.Catalyst.Shape(Emitter(EmitterShape.FAN, 3)), ForgePiece.Catalyst.Essence(Element.FROST)),
            listOf(ForgePiece.Resonator.Modulate(Modulator(ModulatorKind.FORK)), ForgePiece.Resonator.Modulate(Modulator(ModulatorKind.ECHO))),
            seed = 7L,
        )
        assertEquals(DeliveryKind.SURFACE_WAVE, made.lead.delivery.kind)
        assertEquals(Element.FROST, made.lead.element)
        assertEquals(Payload.FLASH_FREEZE, made.lead.payload)
        assertEquals(listOf(ModulatorKind.FORK, ModulatorKind.ECHO), made.lead.modulators.map { it.kind })
        assertTrue(made.name.isNotBlank())
        repeat(50) { k -> assertNotEquals(AttackCode.encode(made), AttackCode.encode(AttackForge.mutate(made, k.toLong()))) }
    }
}
