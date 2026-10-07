package com.stratum.core.domain.attack

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One frame of an attack as plain marks: what every renderer draws.
 *
 * The forge's preview, the scene in play and a test's picture all read the
 * same marks, so an attack looks like itself everywhere. Coordinates are a
 * local frame in blocks: x along the attack's heading, y across it, z up.
 */
sealed interface SketchMark {
    val color: Int
    val alpha: Float

    /** A soft lit blob in the air: a body, a spark, a flash. */
    data class Glow(
        val x: Float, val y: Float, val z: Float, val radius: Float, override val color: Int, override val alpha: Float,
        /** How a renderer that can should cut it; a plain disc otherwise. */
        val form: ParticleShape = ParticleShape.DOT,
        /** Which way it leans, radians, for streaks, feathers and shards. */
        val angle: Float = 0f,
    ) : SketchMark

    /** A mark lying on the ground: a disc, or with [ring] only its rim. */
    data class Ground(val x: Float, val y: Float, val radius: Float, override val color: Int, override val alpha: Float, val ring: Boolean = false) : SketchMark

    /** A lit line: a beam, a crack, a spike, a tether. */
    data class Streak(
        val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float,
        val width: Float, override val color: Int, override val alpha: Float,
    ) : SketchMark
}

/**
 * Draws an [AttackLook] as [SketchMark]s.
 *
 * Each gene changes the marks in its own way, so two looks that differ in
 * any gene draw differently: the body's form, the motif its ornaments and
 * decals are laid out in, the trail behind it, the particle cut, the rhythm
 * its brightness keeps, the flourish of its path, the impact and the scar it
 * leaves, and its size, glow, density, trail length and spin.
 */
object AttackSketch {

    private const val TAU = (PI * 2).toFloat()

    /** Size of a body in blocks. */
    fun bodyRadius(look: AttackLook): Float = 0.22f + 0.07f * look.scale

    /** The look's brightness at [t] seconds: its rhythm. */
    fun pulse(look: AttackLook, t: Float): Float = when (look.rhythm) {
        Rhythm.STEADY -> 1f
        Rhythm.PULSE -> 0.75f + 0.25f * sin(t * 7f)
        Rhythm.BURST -> if ((t * 3f) % 1f < 0.3f) 1f else 0.6f
        Rhythm.STUTTER -> if (((t * 11f).toInt() % 3) == 0) 0.45f else 1f
        Rhythm.CRESCENDO -> 0.55f + 0.45f * ((t * 1.5f) % 1f)
        Rhythm.HEARTBEAT -> { val b = (t * 1.6f) % 1f; if (b < 0.12f || b in 0.22f..0.32f) 1f else 0.6f }
    }

    /** How far off its straight path the body sits at [t]: its flourish. */
    private fun sway(look: AttackLook, t: Float): Pair<Float, Float> = when (look.flourish) {
        Flourish.STRAIGHT -> 0f to 0f
        Flourish.WOBBLE -> sin(t * 9f) * 0.12f to 0f
        Flourish.TWIST -> cos(t * 12f) * 0.1f to sin(t * 12f) * 0.1f
        Flourish.FLICKER -> (if ((t * 17f).toInt() % 2 == 0) 0.07f else -0.07f) to 0f
        Flourish.BREATHE -> 0f to sin(t * 4f) * 0.08f
        Flourish.SPIN -> cos(t * 20f) * 0.05f to sin(t * 20f) * 0.05f
    }

    private fun spinRate(look: AttackLook) = 1.5f + 2.5f * look.spin
    private fun halo(look: AttackLook) = 1.6f + 0.5f * look.glow
    private fun haloAlpha(look: AttackLook) = 0.35f + 0.15f * look.glow
    private fun particles(look: AttackLook) = 3 + 3 * look.density

    /**
     * The motif as points on a unit tile, centred on 0: the arrangement a
     * stone's ornaments, a rune ring's marks and a glyph stamp are laid in.
     */
    fun motif(m: Motif, n: Int = 8): List<Pair<Float, Float>> {
        fun ring(k: Int, r: Float, phase: Float = 0f) = List(k) { i -> val a = phase + TAU * i / k; r * cos(a) to r * sin(a) }
        return when (m) {
            Motif.CHEVRON -> List(n) { i -> val u = i / (n - 1f) * 2f - 1f; abs(u) * 0.6f - 0.3f to u * 0.8f }
            Motif.SPIRAL -> List(n) { i -> val a = i * 0.8f; val r = 0.15f + 0.85f * i / n; r * cos(a) to r * sin(a) }
            Motif.LATTICE -> List(9) { i -> (i % 3 - 1) * 0.6f to (i / 3 - 1) * 0.6f }
            Motif.COMB -> List(n) { i -> if (i == 0) 0f to 0f else -0.7f + 1.4f * (i - 1) / (n - 2f) to if (i % 2 == 0) 0.5f else -0.5f }
            Motif.CRESCENT -> List(n) { i -> val a = PI.toFloat() * (0.2f + 0.6f * i / (n - 1f)); 0.8f * cos(a) to 0.8f * sin(a) - 0.2f }
            Motif.DIAMOND -> listOf(0f to 0.9f, 0.9f to 0f, 0f to -0.9f, -0.9f to 0f, 0f to 0f)
            Motif.ZIGZAG -> List(n) { i -> -0.9f + 1.8f * i / (n - 1f) to if (i % 2 == 0) 0.4f else -0.4f }
            Motif.CONCENTRIC -> ring(6, 0.9f) + ring(4, 0.45f, 0.4f) + listOf(0f to 0f)
            Motif.CROSS -> listOf(0f to 0f, 0.5f to 0f, 1f to 0f, -0.5f to 0f, -1f to 0f, 0f to 0.5f, 0f to 1f, 0f to -0.5f, 0f to -1f)
            Motif.EYE -> ring(n, 0.85f).map { (x, y) -> x to y * 0.45f } + listOf(0f to 0f)
            Motif.SUNBURST -> ring(n, 0.95f) + ring(n, 0.45f, PI.toFloat() / n)
            Motif.LADDER -> List(8) { i -> (if (i % 2 == 0) -0.35f else 0.35f) to -0.9f + 1.8f * (i / 2) / 3f }
            Motif.KNOT -> List(n) { i -> val a = TAU * i / n; 0.8f * sin(2 * a) to 0.8f * sin(a) }
            Motif.NESTED_SQUARES -> listOf(-0.9f to -0.9f, 0.9f to -0.9f, 0.9f to 0.9f, -0.9f to 0.9f, -0.45f to 0f, 0f to -0.45f, 0.45f to 0f, 0f to 0.45f)
            Motif.WAVE -> List(n) { i -> val u = -1f + 2f * i / (n - 1f); u to 0.45f * sin(u * PI.toFloat() * 2f) }
            Motif.TRIANGLES -> listOf(0f to 0.9f, 0.78f to -0.45f, -0.78f to -0.45f, 0f to -0.2f, 0.17f to 0.1f, -0.17f to 0.1f)
            Motif.DOTS -> List(n) { i -> val a = i * 2.399f; val r = 0.9f * sqrt((i + 0.5f) / n); r * cos(a) to r * sin(a) }
            Motif.FAN -> List(n) { i -> val a = PI.toFloat() * (0.15f + 0.7f * i / (n - 1f)); 0.9f * cos(a) to 0.9f * sin(a) - 0.4f } + listOf(0f to -0.4f)
            Motif.STAR -> List(10) { i -> val a = TAU * i / 10 - PI.toFloat() / 2; val r = if (i % 2 == 0) 0.95f else 0.4f; r * cos(a) to r * sin(a) }
            Motif.HOOK -> List(n) { i -> val u = i / (n - 1f); if (u < 0.5f) 0f to -0.9f + 2.4f * u else { val a = (u - 0.5f) * TAU * 0.6f; 0.4f - 0.4f * cos(a) to 0.3f + 0.4f * sin(a) } }
            Motif.ARROW -> listOf(0.9f to 0f, 0.3f to 0f, -0.3f to 0f, -0.9f to 0f, 0.45f to 0.45f, 0.45f to -0.45f)
            Motif.DOT_RING -> ring(n + 4, 0.9f)
            Motif.BRAID -> List(n) { i -> val u = -0.9f + 1.8f * i / (n - 1f); u to (if (i % 2 == 0) 0.3f else -0.3f) * cos(u * 3f) }
            Motif.SCALES -> List(9) { i -> (i % 3 - 1) * 0.55f + (if (i / 3 % 2 == 1) 0.27f else 0f) to (i / 3 - 1) * 0.5f }
        }
    }

    // ---- a body in flight ---------------------------------------------------------------------------

    /**
     * A travelling body at the origin, heading along +x, at [t] seconds into
     * its life, with its trail behind it: a thrown shell, or a ground wave's crest.
     */
    fun body(look: AttackLook, t: Float, kind: DeliveryKind = look.body.delivery): List<SketchMark> {
        val out = ArrayList<SketchMark>()
        val r = bodyRadius(look)
        val p = pulse(look, t)
        val (sy, sz) = sway(look, t)
        trail(look, t, out)
        val spin = t * spinRate(look)
        val form = if (look.body.delivery == kind) look.body else defaultBody(kind)
        when (form) {
            BodyForm.ORB -> {
                out += SketchMark.Glow(0f, sy, sz, r * halo(look), look.primary, haloAlpha(look) * p)
                out += SketchMark.Glow(0f, sy, sz, r, look.core, p)
            }
            BodyForm.SHARD -> {
                out += SketchMark.Glow(0f, sy, sz, r * halo(look), look.primary, haloAlpha(look) * p * 0.8f)
                out += SketchMark.Streak(-r * 1.4f, sy, sz, r * 1.6f, sy, sz, r * 0.9f, look.secondary, 0.9f * p)
                out += SketchMark.Glow(r * 0.9f, sy, sz, r * 0.55f, look.core, p, ParticleShape.TRIANGLE, 0f)
            }
            BodyForm.SPEAR -> {
                out += SketchMark.Streak(-r * 4f, sy, sz, r * 2f, sy, sz, r * 0.45f, look.primary, 0.85f * p)
                out += SketchMark.Glow(r * 2f, sy, sz, r * 0.7f, look.core, p, ParticleShape.TRIANGLE, 0f)
                out += SketchMark.Glow(-r * 3.4f, sy, sz, r * 0.7f, look.secondary, 0.6f * p, ParticleShape.FEATHER, PI.toFloat())
            }
            BodyForm.STONE -> {
                out += SketchMark.Glow(0f, sy, sz, r * 1.2f, look.primary, 0.9f * p, ParticleShape.VOXEL, spin)
                motif(look.motif, 6).forEach { (mx, my) ->
                    val a = spin; val x = mx * cos(a) - my * sin(a); val y = mx * sin(a) + my * cos(a)
                    out += SketchMark.Glow(x * r * 1.6f, sy + y * r * 1.6f, sz, r * 0.22f, look.secondary, p, look.particle)
                }
                out += SketchMark.Glow(0f, sy, sz, r * 0.45f, look.core, p)
            }
            BodyForm.SEED -> {
                for (k in 0..1) {
                    val a = spin + PI.toFloat() * k
                    out += SketchMark.Glow(0f, sy + cos(a) * r * 0.8f, sz + sin(a) * r * 0.8f, r * 0.75f, if (k == 0) look.primary else look.secondary, 0.85f * p)
                }
                out += SketchMark.Glow(0f, sy, sz, r * 0.4f, look.core, p)
            }
            BodyForm.RIPPLE -> for (k in 0..2) {
                val g = ((t * 2f + k / 3f) % 1f)
                out += SketchMark.Ground(-k * 0.3f, sy, r * (1f + 2.5f * g), look.primary, (1f - g) * 0.8f * p, ring = true)
            }
            BodyForm.SERPENT -> for (k in 0..6) {
                val x = -k * r * 1.1f
                out += SketchMark.Glow(x, sin(t * 9f - k * 0.9f) * r * 1.4f, r * 0.4f, r * (1f - k * 0.1f), if (k == 0) look.core else look.primary, (1f - k * 0.12f) * p)
            }
            BodyForm.ROOTS -> for (k in 0..4) {
                val a = (k - 2) * 0.45f
                out += SketchMark.Streak(-r * 3f, 0f, 0f, r * 1.5f * cos(a), r * 1.5f * sin(a), 0.05f, r * 0.25f, look.primary, 0.8f * p)
                out += SketchMark.Glow(r * 1.5f * cos(a), r * 1.5f * sin(a), 0.1f, r * 0.3f, look.secondary, p, look.particle)
            }
            BodyForm.SPIKES -> for (k in 0..3) {
                val x = -k * r * 1.4f; val h = r * (3f - k * 0.6f) * (0.7f + 0.3f * sin(t * 14f + k))
                out += SketchMark.Streak(x, sy, 0f, x, sy, h, r * 0.5f, if (k == 0) look.core else look.primary, (1f - k * 0.2f) * p)
            }
            else -> {
                out += SketchMark.Glow(0f, sy, sz, r * halo(look), look.primary, haloAlpha(look) * p)
                out += SketchMark.Glow(0f, sy, sz, r, look.core, p)
            }
        }
        return out
    }

    private fun defaultBody(kind: DeliveryKind) = when (kind) {
        DeliveryKind.SURFACE_WAVE -> BodyForm.RIPPLE
        else -> BodyForm.ORB
    }

    /** What streams behind a body, along -x. */
    private fun trail(look: AttackLook, t: Float, out: MutableList<SketchMark>) {
        val r = bodyRadius(look)
        val length = 0.8f + 0.7f * look.trailLength
        val n = 3 + 2 * look.trailLength
        fun h(i: Int, salt: Int) = hash(i * 31 + salt + look.motif.ordinal * 7)
        when (look.trail) {
            TrailStyle.NONE -> Unit
            TrailStyle.RIBBON -> for (i in 1..n) {
                val x = -length * i / n
                out += SketchMark.Glow(x, sin(t * 10f + i * 0.9f) * r * 0.9f, 0f, r * 0.45f * (1f - i / (n + 1f)), look.secondary, 0.7f * (1f - i / (n + 1f)))
            }
            TrailStyle.SPARKS -> for (i in 1..n + 2) {
                val x = -length * h(i, 1); val y = (h(i, 2) - 0.5f) * r * 3f
                out += SketchMark.Glow(x, y, (h(i, 3) - 0.5f) * r * 2f, r * 0.15f, look.core, 0.9f * (1f + x / length), look.particle, h(i, 4) * TAU)
            }
            TrailStyle.SMOKE -> for (i in 1..n) {
                val x = -length * i / n
                out += SketchMark.Glow(x, 0f, i * 0.04f, r * (0.8f + 0.25f * i), look.secondary, 0.25f * (1f - i / (n + 1f)))
            }
            TrailStyle.SHARDS -> for (i in 1..n) {
                out += SketchMark.Glow(-length * i / n, (h(i, 5) - 0.5f) * r * 2f, 0f, r * 0.3f, look.primary, 0.8f * (1f - i / (n + 1f)), ParticleShape.TRIANGLE, h(i, 6) * TAU)
            }
            TrailStyle.GLYPHS -> motif(look.motif, n).forEachIndexed { i, (mx, my) ->
                out += SketchMark.Glow(-length * (0.2f + 0.8f * (mx + 1f) / 2f), my * r * 1.5f, 0f, r * 0.18f, look.secondary, 0.8f - 0.05f * i, look.particle)
            }
            TrailStyle.AFTERIMAGE -> for (i in 1..3) {
                out += SketchMark.Glow(-length * i / 3f, 0f, 0f, r * (1.1f - 0.15f * i), look.primary, 0.45f / i)
            }
            TrailStyle.DRIP -> for (i in 1..n) {
                val x = -length * i / n
                out += SketchMark.Glow(x, 0f, -((t * 3f + i * 0.3f) % 1f) * 0.6f, r * 0.2f, look.primary, 0.75f, ParticleShape.DROPLET)
            }
            TrailStyle.EMBERS -> for (i in 1..n + 2) {
                val x = -length * h(i, 7)
                out += SketchMark.Glow(x, (h(i, 8) - 0.5f) * r * 2f, ((t * 1.5f + h(i, 9)) % 1f) * 0.8f, r * 0.13f, look.core, 0.9f * (1f + x / length), look.particle)
            }
            TrailStyle.STREAKS -> for (k in -1..1) {
                out += SketchMark.Streak(-length * (1f - 0.2f * abs(k)), k * r * 0.5f, 0f, -r, k * r * 0.5f, 0f, r * 0.15f, look.secondary, 0.65f)
            }
        }
    }

    // ---- beams, tethers, fields and orbits -------------------------------------------------------------

    /** A line from the origin to [length] along +x: an instant ray or a tether. */
    fun beam(look: AttackLook, length: Float, t: Float, kind: DeliveryKind = look.body.delivery): List<SketchMark> {
        val out = ArrayList<SketchMark>()
        val r = bodyRadius(look); val p = pulse(look, t)
        val form = if (look.body.delivery == kind) look.body else if (kind == DeliveryKind.TETHER) BodyForm.THREAD else BodyForm.FLAT_BEAM
        val steps = max(4, (length * 2f).toInt())
        when (form) {
            BodyForm.FLAT_BEAM -> {
                out += SketchMark.Streak(0f, 0f, 0.4f, length, 0f, 0.4f, r * 1.6f, look.primary, 0.5f * p)
                out += SketchMark.Streak(0f, 0f, 0.4f, length, 0f, 0.4f, r * 0.5f, look.core, p)
            }
            BodyForm.BRAIDED_BEAM -> for (s in 0..1) for (i in 0 until steps) {
                val a = i.toFloat() / steps; val b = (i + 1f) / steps
                fun y(u: Float) = sin(u * length * 3f + t * 12f + s * PI.toFloat()) * r * 0.8f
                out += SketchMark.Streak(a * length, y(a), 0.4f, b * length, y(b), 0.4f, r * 0.35f, if (s == 0) look.primary else look.secondary, 0.9f * p)
            }
            BodyForm.PULSE_BEAM -> {
                out += SketchMark.Streak(0f, 0f, 0.4f, length, 0f, 0.4f, r * 0.4f, look.primary, 0.6f * p)
                for (k in 0..2) { val u = ((t * 2.5f + k / 3f) % 1f) * length; out += SketchMark.Glow(u, 0f, 0.4f, r * 1.3f, look.core, 0.9f) }
            }
            BodyForm.SEGMENT_BEAM -> for (i in 0 until steps step 2) {
                out += SketchMark.Streak(i * length / steps, 0f, 0.4f, (i + 1) * length / steps, 0f, 0.4f, r * 0.8f, if (i % 4 == 0) look.primary else look.secondary, p)
            }
            BodyForm.CHAIN_LINK -> for (i in 0 until steps) {
                val x = (i + 0.5f) * length / steps
                out += SketchMark.Glow(x, 0f, 0.4f, r * 0.45f, look.primary, 0.9f * p, ParticleShape.RING, if (i % 2 == 0) 0f else PI.toFloat() / 2)
            }
            BodyForm.THREAD -> {
                for (i in 0 until steps) {
                    val a = i.toFloat() / steps; val b = (i + 1f) / steps
                    fun z(u: Float) = 0.4f - sin(u * PI.toFloat()) * 0.25f
                    out += SketchMark.Streak(a * length, 0f, z(a), b * length, 0f, z(b), r * 0.18f, look.primary, 0.9f * p)
                }
            }
            BodyForm.ARC -> {
                var px = 0f; var py = 0f
                for (i in 1..steps) {
                    val x = i * length / steps; val y = if (i == steps) 0f else (hash(i * 13 + (t * 20f).toInt()) - 0.5f) * r * 2.5f
                    out += SketchMark.Streak(px, py, 0.4f, x, y, 0.4f, r * 0.25f, look.core, p)
                    px = x; py = y
                }
                out += SketchMark.Streak(0f, 0f, 0.4f, length, 0f, 0.4f, r * 1.2f, look.primary, 0.3f * p)
            }
            BodyForm.GLYPH_ROPE -> motif(look.motif, steps).forEachIndexed { i, (_, my) ->
                out += SketchMark.Glow((i + 0.5f) * length / steps, my * r, 0.4f, r * 0.3f, if (i % 2 == 0) look.primary else look.secondary, p, look.particle)
            }
            else -> out += SketchMark.Streak(0f, 0f, 0.4f, length, 0f, 0.4f, r * 0.5f, look.primary, p)
        }
        // Sparks along the line, as many as the look is dense.
        for (i in 0 until particles(look) / 2) {
            val u = (hash(i * 17 + 3) + t * 0.7f) % 1f
            out += SketchMark.Glow(u * length, (hash(i * 5 + 1) - 0.5f) * r * 2f, 0.4f, r * 0.12f, look.core, 0.8f, look.particle, hash(i) * TAU)
        }
        return out
    }

    /** A standing field of [radius] around the origin: a rune circle, pillars, a vortex, a totem. */
    fun field(look: AttackLook, radius: Float, t: Float, kind: DeliveryKind = look.body.delivery): List<SketchMark> {
        val out = ArrayList<SketchMark>()
        val p = pulse(look, t)
        val spin = t * spinRate(look) * 0.4f
        val form = if (look.body.delivery == kind) look.body else BodyForm.RUNE_CIRCLE
        out += SketchMark.Ground(0f, 0f, radius, look.primary, 0.22f * p)
        out += SketchMark.Ground(0f, 0f, radius, look.secondary, 0.7f * p, ring = true)
        when (form) {
            BodyForm.RUNE_CIRCLE -> motif(look.motif, 10).forEach { (mx, my) ->
                val x = mx * cos(spin) - my * sin(spin); val y = mx * sin(spin) + my * cos(spin)
                out += SketchMark.Glow(x * radius * 0.75f, y * radius * 0.75f, 0.05f, radius * 0.07f, look.core, 0.9f * p, look.particle)
            }
            BodyForm.PILLAR_FIELD -> for (k in 0 until 3 + look.density) {
                val a = spin + TAU * k / (3 + look.density); val d = radius * 0.6f
                val h = 1.2f + 0.6f * sin(t * 5f + k)
                out += SketchMark.Streak(d * cos(a), d * sin(a), 0f, d * cos(a), d * sin(a), h, radius * 0.12f, look.primary, 0.85f * p)
                out += SketchMark.Glow(d * cos(a), d * sin(a), h, radius * 0.1f, look.core, p)
            }
            BodyForm.VORTEX -> for (k in 0 until 10 + 3 * look.density) {
                val u = k / (10f + 3 * look.density); val a = spin * 3f + u * TAU * 2f
                out += SketchMark.Glow(radius * u * cos(a), radius * u * sin(a), 0.1f + u * 0.4f, radius * 0.06f, if (k % 2 == 0) look.primary else look.secondary, 0.9f * p, look.particle, a)
            }
            BodyForm.TOTEM -> {
                out += SketchMark.Streak(0f, 0f, 0f, 0f, 0f, 2f, radius * 0.2f, look.primary, 0.9f * p)
                motif(look.motif, 6).take(6).forEachIndexed { i, (mx, _) ->
                    out += SketchMark.Glow(mx * radius * 0.15f, 0f, 0.4f + i * 0.3f, radius * 0.1f, look.secondary, p, look.particle)
                }
                out += SketchMark.Glow(0f, 0f, 2.1f, radius * 0.22f, look.core, p)
            }
            else -> Unit
        }
        return out
    }

    /** Bodies circling the origin at [radius]: wisps, blades, moons, masklets. */
    fun orbit(look: AttackLook, radius: Float, t: Float, count: Int = 3): List<SketchMark> {
        val out = ArrayList<SketchMark>()
        val r = bodyRadius(look); val p = pulse(look, t)
        val n = count.coerceIn(2, 8)
        for (k in 0 until n) {
            val a = t * spinRate(look) + TAU * k / n
            val x = radius * cos(a); val y = radius * sin(a); val z = 0.8f
            when (look.body) {
                BodyForm.BLADES -> out += SketchMark.Streak(x - sin(a) * r, y + cos(a) * r, z, x + sin(a) * r, y - cos(a) * r, z, r * 0.35f, look.primary, p)
                BodyForm.MOONS -> {
                    out += SketchMark.Glow(x, y, z, r, look.primary, 0.9f * p)
                    out += SketchMark.Glow(x + r * 0.35f * cos(a), y + r * 0.35f * sin(a), z, r * 0.8f, look.secondary, 0.6f * p)
                }
                BodyForm.MASKLETS -> {
                    out += SketchMark.Glow(x, y, z, r * 1.1f, look.primary, 0.9f * p, ParticleShape.DROPLET, a)
                    out += SketchMark.Glow(x - r * 0.3f, y, z + r * 0.2f, r * 0.18f, look.core, p)
                    out += SketchMark.Glow(x + r * 0.3f, y, z + r * 0.2f, r * 0.18f, look.core, p)
                }
                else -> {
                    out += SketchMark.Glow(x, y, z, r * halo(look) * 0.7f, look.primary, haloAlpha(look) * p)
                    out += SketchMark.Glow(x, y, z, r * 0.5f, look.core, p)
                }
            }
            // A short arc of trail behind each.
            for (i in 1..1 + look.trailLength) {
                val b = a - i * 0.18f
                out += SketchMark.Glow(radius * cos(b), radius * sin(b), z, r * (0.5f - 0.08f * i), look.secondary, 0.5f / i, look.particle)
            }
        }
        return out
    }

    // ---- landing --------------------------------------------------------------------------------------

    /** The impact at the origin, [k] of the way through (0 as it lands, 1 as it fades). */
    fun impact(look: AttackLook, radius: Float, k: Float): List<SketchMark> {
        val out = ArrayList<SketchMark>()
        val f = 1f - k.coerceIn(0f, 1f)
        val n = particles(look)
        when (look.impact) {
            ImpactSignature.FLASH -> {
                out += SketchMark.Glow(0f, 0f, 0.5f, radius * (0.6f + 0.8f * k), look.core, f)
                out += SketchMark.Glow(0f, 0f, 0.5f, radius * (1f + k), look.primary, 0.5f * f)
            }
            ImpactSignature.SHOCKWAVE -> {
                out += SketchMark.Ground(0f, 0f, radius * (0.2f + 1.1f * k), look.primary, f, ring = true)
                out += SketchMark.Ground(0f, 0f, radius * (0.1f + 0.7f * k), look.secondary, 0.6f * f, ring = true)
            }
            ImpactSignature.PILLAR -> {
                out += SketchMark.Streak(0f, 0f, 0f, 0f, 0f, 1f + 2.5f * k, radius * 0.35f * f + 0.05f, look.primary, f)
                out += SketchMark.Glow(0f, 0f, 1f + 2.5f * k, radius * 0.3f, look.core, f)
            }
            ImpactSignature.BLOOM -> for (i in 0 until n) {
                val a = TAU * i / n; val d = radius * k
                out += SketchMark.Glow(d * cos(a), d * sin(a), 0.4f + 0.3f * k, radius * 0.18f * f + 0.03f, if (i % 2 == 0) look.primary else look.secondary, f, ParticleShape.LEAF, a)
            }
            ImpactSignature.CRACK_STAR -> for (i in 0 until 5 + look.density) {
                val a = TAU * i / (5 + look.density) + hash(i) * 0.4f; val d = radius * (0.5f + 0.7f * min(1f, k * 2f))
                out += SketchMark.Streak(0f, 0f, 0.02f, d * cos(a), d * sin(a), 0.02f, 0.08f, look.core, f)
            }
            ImpactSignature.IMPLOSION -> {
                out += SketchMark.Ground(0f, 0f, radius * (1.2f - k), look.primary, f, ring = true)
                for (i in 0 until n) { val a = TAU * i / n + k; val d = radius * (1.2f - k)
                    out += SketchMark.Glow(d * cos(a), d * sin(a), 0.3f, 0.08f, look.core, f, look.particle) }
            }
            ImpactSignature.SPLASH -> for (i in 0 until n) {
                val a = TAU * i / n; val d = radius * k
                out += SketchMark.Glow(d * cos(a), d * sin(a), 0.2f + sin(k * PI.toFloat()) * 0.8f, 0.1f, look.primary, f, ParticleShape.DROPLET, a)
            }
            ImpactSignature.GLYPH_STAMP -> motif(look.motif, 10).forEach { (mx, my) ->
                out += SketchMark.Ground(mx * radius * 0.8f, my * radius * 0.8f, radius * 0.12f, look.core, f)
                out += SketchMark.Glow(mx * radius * 0.8f, my * radius * 0.8f, 0.2f + k, 0.08f, look.secondary, f, look.particle)
            }
        }
        return out
    }

    /** The scar it leaves, at the origin; [fade] 0 fresh, 1 gone. */
    fun decal(look: AttackLook, radius: Float, fade: Float = 0f): List<SketchMark> {
        val a = (1f - fade).coerceIn(0f, 1f)
        val dark = AttackLook.hsv(0f, 0f, 0.08f)
        return when (look.decal) {
            GroundDecal.NONE -> emptyList()
            GroundDecal.SCORCH -> listOf(SketchMark.Ground(0f, 0f, radius, dark, 0.55f * a), SketchMark.Ground(0f, 0f, radius * 0.5f, look.primary, 0.3f * a))
            GroundDecal.RIME -> listOf(SketchMark.Ground(0f, 0f, radius, AttackLook.hsv(195f, 0.15f, 1f), 0.4f * a), SketchMark.Ground(0f, 0f, radius, look.secondary, 0.6f * a, ring = true))
            GroundDecal.CRACKS -> List(6) { i -> val t = TAU * i / 6 + hash(i + 40) * 0.5f
                SketchMark.Streak(0f, 0f, 0.01f, radius * cos(t), radius * sin(t), 0.01f, 0.07f, dark, 0.8f * a) }
            GroundDecal.RUNE_RING -> listOf(SketchMark.Ground(0f, 0f, radius, look.secondary, 0.7f * a, ring = true)) +
                motif(look.motif, 8).map { (mx, my) -> SketchMark.Ground(mx * radius * 0.7f, my * radius * 0.7f, radius * 0.08f, look.primary, 0.8f * a) }
            GroundDecal.POOL -> listOf(SketchMark.Ground(0f, 0f, radius * 0.8f, look.primary, 0.5f * a), SketchMark.Ground(radius * 0.4f, radius * 0.2f, radius * 0.4f, look.secondary, 0.4f * a))
            GroundDecal.ASH -> List(7) { i -> SketchMark.Ground((hash(i) - 0.5f) * radius * 1.6f, (hash(i + 9) - 0.5f) * radius * 1.6f, radius * 0.2f, AttackLook.hsv(0f, 0f, 0.4f), 0.5f * a) }
            GroundDecal.GLASS -> listOf(SketchMark.Ground(0f, 0f, radius, look.core, 0.35f * a), SketchMark.Ground(0f, 0f, radius * 0.6f, look.core, 0.6f * a, ring = true))
        }
    }

    // ---- the whole attack, staged ------------------------------------------------------------------------

    /** The stage a preview plays on: caster at x = 0, target at [TARGET]. */
    const val TARGET = 7f

    /** Seconds one preview loop takes. */
    const val LOOP = 2.4f

    /**
     * The whole of [skill] playing out on a stage, caster at the origin and
     * a target [TARGET] blocks ahead, at [t] seconds (looping every [LOOP]):
     * its lead phase flies, lands and scars, and the first chained attack
     * goes off where it landed.
     */
    fun preview(skill: ProceduralSkill, t: Float): List<SketchMark> {
        val out = ArrayList<SketchMark>()
        val u = (t % LOOP) / LOOP
        phase(skill.lead, skill.look, u, 0f, 0f, 0f, 1f, t, out)
        // A chained attack: smaller, after the first lands, where it lands.
        skill.lead.subTriggers.entries.firstOrNull()?.let { (_, sub) ->
            val v = ((u - 0.55f) / 0.45f)
            if (v in 0f..1f) phase(sub.lead, sub.look, v, TARGET, 0f, 0f, 0.6f, t, out)
        }
        return out
    }

    private fun phase(phase: SkillPhase, look: AttackLook, u: Float, ox: Float, oy: Float, heading: Float, size: Float, t: Float, out: MutableList<SketchMark>) {
        val kind = phase.delivery.kind
        val reach = TARGET * size
        val travel = 0.55f
        val g = phase.geometry
        val headings: List<Float> = when (g.shape) {
            EmitterShape.SINGLE, EmitterShape.PIERCING_LINE, EmitterShape.CHAIN, EmitterShape.RUPTURE_GRID -> listOf(0f)
            EmitterShape.NOVA -> List(max(6, g.count * 2)) { TAU * it / max(6, g.count * 2) }
            EmitterShape.CONE, EmitterShape.FAN -> { val n = max(3, g.count); val s = (g.spreadDegrees.coerceIn(20f, 120f)) * PI.toFloat() / 180f; List(n) { -s / 2 + s * it / (n - 1) } }
            EmitterShape.HELIX -> List(max(2, g.count)) { TAU * it / max(2, g.count) }
        }
        fun place(marks: List<SketchMark>, x: Float, y: Float, angle: Float) = marks.forEach { out += transform(it, x, y, angle) }
        val impactAt = ox + cos(heading) * reach to oy + sin(heading) * reach
        when (kind) {
            DeliveryKind.BALLISTIC, DeliveryKind.SURFACE_WAVE -> if (u < travel) {
                val d = u / travel
                headings.forEach { h ->
                    val a = heading + h
                    val dist = if (g.shape == EmitterShape.NOVA) reach * 0.5f * d else reach * d
                    val wobble = if (phase.has(ModulatorKind.SINE_WAVE)) sin(d * 12f) * 0.6f else 0f
                    val helix = if (g.shape == EmitterShape.HELIX) sin(d * 10f + h) * 0.8f else 0f
                    val lift = if (kind == DeliveryKind.BALLISTIC) sin(d * PI.toFloat()) * 1.2f * size else 0f
                    val x = ox + cos(a) * dist - sin(a) * (wobble + helix); val y = oy + sin(a) * dist + cos(a) * (wobble + helix)
                    body(look, t, kind).forEach { m -> out += transform(lifted(m, lift), x, y, a) }
                }
            }
            DeliveryKind.INSTANT_RAY, DeliveryKind.TETHER -> if (u < travel) {
                headings.forEach { h -> place(beam(look, if (g.shape == EmitterShape.NOVA) reach * 0.5f else reach, t, kind), ox, oy, heading + h) }
                if (g.shape == EmitterShape.CHAIN) for (j in 1..g.count.coerceAtMost(3)) {
                    val sx = impactAt.first + (j - 1) * 1.6f; val sy = impactAt.second + if (j % 2 == 0) 1.2f else -1.2f
                    place(beam(look, 1.8f, t + j, kind), sx, sy - (if (j % 2 == 0) 1.2f else -1.2f), if (j % 2 == 0) 0.6f else -0.6f)
                }
            }
            DeliveryKind.IMPACT_FIELD -> {
                val grow = min(1f, u / 0.25f)
                if (g.shape == EmitterShape.RUPTURE_GRID) for (i in -1..1) for (j in -1..1) {
                    val w = ((u * 3f + (i + j + 2) * 0.12f) % 1f)
                    place(field(look, 0.7f * size * grow, t, kind), impactAt.first + i * 1.6f * size, impactAt.second + j * 1.6f * size, 0f)
                    if (w < 0.3f) place(impact(look, 0.8f * size, w / 0.3f), impactAt.first + i * 1.6f * size, impactAt.second + j * 1.6f * size, 0f)
                } else place(field(look, (1.4f + 0.3f * phase.delivery.a) * size * grow, t, kind), impactAt.first, impactAt.second, 0f)
            }
            DeliveryKind.ORBITAL -> {
                val launch = u > 0.45f
                if (!launch) place(orbit(look, 1.3f * size, t, max(2, g.count)), ox, oy, 0f)
                else if (u < 0.75f) {
                    val d = (u - 0.45f) / 0.3f
                    place(body(look, t, DeliveryKind.BALLISTIC), ox + cos(heading) * reach * d, oy + sin(heading) * reach * d, heading)
                }
            }
        }
        // Landing: impact, then the scar.
        val radius = (1.2f + 0.25f * phase.delivery.b) * size
        if (u >= travel - 0.05f && kind != DeliveryKind.IMPACT_FIELD) {
            val k = (u - travel + 0.05f) / (1f - travel + 0.05f)
            place(decal(look, radius, max(0f, k - 0.5f) * 2f), impactAt.first, impactAt.second, 0f)
            if (k < 0.6f) place(impact(look, radius, k / 0.6f), impactAt.first, impactAt.second, 0f)
        }
        if (kind == DeliveryKind.IMPACT_FIELD && u > 0.6f) place(decal(look, radius, (u - 0.6f) * 2f), impactAt.first, impactAt.second, 0f)
    }

    private fun lifted(m: SketchMark, dz: Float): SketchMark = if (dz == 0f) m else when (m) {
        is SketchMark.Glow -> m.copy(z = m.z + dz)
        is SketchMark.Streak -> m.copy(z0 = m.z0 + dz, z1 = m.z1 + dz)
        is SketchMark.Ground -> m
    }

    /** [m] turned by [angle] and moved to ([x], [y]). */
    fun transform(m: SketchMark, x: Float, y: Float, angle: Float): SketchMark {
        val c = cos(angle); val s = sin(angle)
        fun rx(px: Float, py: Float) = x + px * c - py * s
        fun ry(px: Float, py: Float) = y + px * s + py * c
        return when (m) {
            is SketchMark.Glow -> m.copy(x = rx(m.x, m.y), y = ry(m.x, m.y), angle = m.angle + angle)
            is SketchMark.Ground -> m.copy(x = rx(m.x, m.y), y = ry(m.x, m.y))
            is SketchMark.Streak -> m.copy(x0 = rx(m.x0, m.y0), y0 = ry(m.x0, m.y0), x1 = rx(m.x1, m.y1), y1 = ry(m.x1, m.y1))
        }
    }

    /** A steady 0..1 from an integer: the sketch's dice, the same on every device. */
    private fun hash(i: Int): Float {
        var h = i * -0x61c88647
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12)
        return (h ushr 8) / 16777216f
    }
}
