package com.stratum.core.domain.attack

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Every gene of a look shows in what is drawn, and random attacks draw differently. */
class AttackSketchTest {

    private val base = AttackForge.build(
        SkillPhase(Delivery(DeliveryKind.BALLISTIC), Emitter(EmitterShape.SINGLE), payload = Payload.BURN), 1L,
    ).look.copy(body = BodyForm.ORB, trail = TrailStyle.SPARKS, rhythm = Rhythm.PULSE, flourish = Flourish.WOBBLE)

    /** Everything a look draws in one frame: body, beam, field, orbit, impact and scar. */
    private fun all(look: AttackLook, t: Float = 0.37f): List<SketchMark> =
        AttackSketch.body(look, t) + AttackSketch.beam(look, 5f, t, if (look.body.delivery == DeliveryKind.TETHER) DeliveryKind.TETHER else DeliveryKind.INSTANT_RAY) + AttackSketch.field(look, 2f, t, DeliveryKind.IMPACT_FIELD) +
            AttackSketch.orbit(look, 1.3f, t) + AttackSketch.impact(look, 1.5f, 0.4f) + AttackSketch.decal(look, 1.5f)

    @Test
    fun `changing any one gene changes the drawing`() {
        fun <T> each(values: List<T>, from: AttackLook = base, change: AttackLook.(T) -> AttackLook) = values.forEach { v ->
            val other = from.change(v)
            if (other != from) assertNotEquals(all(from), all(other), "no visible change for $v")
        }
        // Motif and spin show most on an ornamented stone.
        val stone = base.copy(body = BodyForm.STONE)
        // A body shows on its own delivery: each differs from every other of that delivery.
        BodyForm.entries.groupBy { it.delivery }.values.forEach { bodies ->
            val drawn = bodies.map { all(base.copy(body = it)) }
            assertTrue(drawn.toSet().size == bodies.size, "two bodies draw alike: $bodies")
        }
        each(TrailStyle.entries) { copy(trail = it) }
        each(Motif.entries, stone) { copy(motif = it) }
        each(ParticleShape.entries) { copy(particle = it) }
        each(Rhythm.entries) { copy(rhythm = it) }
        each(Flourish.entries) { copy(flourish = it) }
        each(ImpactSignature.entries) { copy(impact = it) }
        each(GroundDecal.entries) { copy(decal = it) }
        each((0..3).toList()) { copy(scale = it) }
        each((0..3).toList()) { copy(glow = it) }
        each((0..3).toList()) { copy(density = it) }
        each((0..3).toList()) { copy(trailLength = it) }
        each((0..3).toList(), stone) { copy(spin = it) }
        each(listOf(0xFF123456.toInt(), 0xFFFF0000.toInt())) { copy(primary = it) }
        each(listOf(0xFF654321.toInt())) { copy(secondary = it) }
        each(listOf(0xFF00FF00.toInt())) { copy(core = it) }
    }

    @Test
    fun `motifs are all different shapes`() {
        val shapes = Motif.entries.map { AttackSketch.motif(it).map { (x, y) -> (x * 100).toInt() to (y * 100).toInt() } }
        assertTrue(shapes.toSet().size == Motif.entries.size)
    }

    @Test
    fun `random attacks draw differently`() {
        val n = 3_000
        val frames = HashSet<Int>(n * 2)
        for (k in 0 until n) {
            val skill = AttackForge.roll(k * 7919L + 11)
            frames += AttackSketch.preview(skill, 0.9f).hashCode() * 31 + AttackSketch.preview(skill, 1.6f).hashCode()
        }
        assertTrue(frames.size > n * 0.995, "distinct frames: ${frames.size} of $n")
    }

    @Test
    fun `every delivery and shape draws something at every moment`() {
        for (kind in DeliveryKind.entries) for (shape in EmitterShape.entries) {
            val skill = AttackForge.build(SkillPhase(Delivery(kind), Emitter(shape, 3), payload = Payload.STAGGER), 5L)
            for (i in 0 until 12) {
                val t = AttackSketch.LOOP * i / 12f
                val marks = AttackSketch.preview(skill, t)
                assertTrue(marks.all { m -> listOf(m.alpha).all(Float::isFinite) })
                if (i in 1..5) assertTrue(marks.isNotEmpty(), "$kind $shape drew nothing at $t")
            }
        }
    }
}
