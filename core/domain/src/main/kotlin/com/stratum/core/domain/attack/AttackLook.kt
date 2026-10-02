package com.stratum.core.domain.attack

import java.math.BigInteger
import kotlin.math.abs
import kotlin.random.Random

/**
 * How an attack looks: a visual genome every renderer reads the same way.
 *
 * Half of it is dictated by what the attack does, so mechanics can be read at
 * a glance: its element sets the colour family, its delivery the body (a ray's
 * profile, a thrown shell's form, a ground wave's crest), its payload the
 * impact and the mark left on the ground, its modulators the trail. The other
 * half is a style roll from the attack's seed: the palette's harmony and
 * shade, a glyph motif stamped through its decals and particles, the particle
 * shape, the rhythm it pulses to, its flourish, and how big, bright, dense,
 * long-tailed and fast-spinning it is.
 *
 * The categorical genes alone make billions of distinct looks ([space]);
 * every one is drawn differently by [com.stratum.core.domain.attack] readers
 * in the scene and in the forge's preview.
 */
data class AttackLook(
    val primary: Int,
    val secondary: Int,
    val core: Int,
    val harmony: Harmony,
    val hueStep: Int,
    val shade: Int,
    val body: BodyForm,
    val motif: Motif,
    val trail: TrailStyle,
    val particle: ParticleShape,
    val rhythm: Rhythm,
    val flourish: Flourish,
    val impact: ImpactSignature,
    val decal: GroundDecal,
    /** 0..3 each: how big, how bright, how many particles, how long its trail, how fast it turns. */
    val scale: Int,
    val glow: Int,
    val density: Int,
    val trailLength: Int,
    val spin: Int,
) {
    /** Every gene in one number: two attacks with the same signature are drawn the same. */
    val signature: Long
        get() {
            var h = 1469598103934665603L
            fun mix(v: Int) { h = (h xor v.toLong()) * 1099511628211L }
            listOf(harmony.ordinal, hueStep, shade, body.ordinal, motif.ordinal, trail.ordinal, particle.ordinal, rhythm.ordinal,
                flourish.ordinal, impact.ordinal, decal.ordinal, scale, glow, density, trailLength, spin, primary, secondary, core).forEach(::mix)
            return h
        }

    /** A few words for a tooltip: "violet crescent-glyph shards, pulsing". */
    val summary: String get() = "${trail.label.lowercase()} ${body.label.lowercase()} in ${motif.label.lowercase()} glyphs, ${rhythm.label.lowercase()}"

    companion object {
        /** How many hue steps a palette may shift from its element's family. */
        const val HUE_STEPS = 16
        const val SHADES = 4
        const val SCALARS = 4

        /** The look of [skill]: its lead phase's mechanics, styled by its seed. */
        fun of(skill: ProceduralSkill): AttackLook = of(skill.lead, skill.seed)

        fun of(phase: SkillPhase, seed: Long): AttackLook {
            val r = Random(seed * 0x5DEECE66DL + 0x1F3L)
            val hueStep = r.nextInt(HUE_STEPS)
            val shade = r.nextInt(SHADES)
            val harmony = Harmony.entries[r.nextInt(Harmony.entries.size)]
            val (hue, sat, value) = family(phase.element)
            val h = hue + (hueStep - HUE_STEPS / 2) * 4.5f
            val s = (sat * (0.75f + 0.1f * shade)).coerceIn(0f, 1f)
            val v = (value * (0.8f + 0.07f * shade)).coerceIn(0.2f, 1f)
            val primary = hsv(h, s, v)
            val secondary = hsv(h + harmony.offset, (s * harmony.saturation).coerceIn(0f, 1f), (v * harmony.value).coerceIn(0.1f, 1f))
            val core = when (phase.payload.family) {
                PayloadFamily.AFFLICTION -> hsv(h, s * 0.25f, 1f)       // white-hot
                PayloadFamily.KINETIC -> hsv(h + 20f, s * 0.5f, 0.95f)  // pale
                PayloadFamily.VOXEL -> hsv(h - 10f, s * 0.8f, 0.3f)     // dark, earthy heart
            }
            val bodies = BodyForm.entries.filter { it.delivery == phase.delivery.kind }
            return AttackLook(
                primary = primary, secondary = secondary, core = core, harmony = harmony, hueStep = hueStep, shade = shade,
                body = bodies[r.nextInt(bodies.size)],
                motif = Motif.entries[r.nextInt(Motif.entries.size)],
                trail = trailFor(phase, r),
                particle = ParticleShape.entries[r.nextInt(ParticleShape.entries.size)],
                rhythm = Rhythm.entries[r.nextInt(Rhythm.entries.size)],
                flourish = Flourish.entries[r.nextInt(Flourish.entries.size)],
                impact = impactFor(phase.payload, r),
                decal = decalFor(phase.payload, r),
                scale = r.nextInt(SCALARS), glow = r.nextInt(SCALARS), density = r.nextInt(SCALARS), trailLength = r.nextInt(SCALARS), spin = r.nextInt(SCALARS),
            )
        }

        /**
         * How many looks there are. [scalars] false counts only the
         * categorical genes and the palette's hue and shade; true also counts
         * the five 0..3 scalars.
         */
        fun space(scalars: Boolean = false): BigInteger {
            fun n(x: Int) = BigInteger.valueOf(x.toLong())
            // Each delivery has its own bodies; the element families give the palette its base.
            val palette = n(Element.entries.size) * n(HUE_STEPS) * n(SHADES) * n(Harmony.entries.size)
            // Impacts and ground marks follow the payload: only the pairs a payload can show are counted.
            val marks = Payload.entries.sumOf { impactsFor(it).size * decalsFor(it).size }
            val categorical = n(BodyForm.entries.size) * n(Motif.entries.size) * n(TrailStyle.entries.size) * n(ParticleShape.entries.size) *
                n(Rhythm.entries.size) * n(Flourish.entries.size) * n(marks)
            return palette * categorical * (if (scalars) n(SCALARS).pow(5) else BigInteger.ONE)
        }

        /** An element's colour family: hue in degrees, saturation, value. */
        fun family(e: Element): Triple<Float, Float, Float> = when (e) {
            Element.PHYSICAL -> Triple(38f, 0.25f, 0.9f)
            Element.FIRE -> Triple(22f, 0.95f, 1f)
            Element.FROST -> Triple(195f, 0.6f, 1f)
            Element.STORM -> Triple(205f, 0.85f, 1f)
            Element.VENOM -> Triple(110f, 0.85f, 0.85f)
            Element.SPIRIT -> Triple(280f, 0.55f, 1f)
            Element.EARTH -> Triple(30f, 0.7f, 0.6f)
            Element.SHADOW -> Triple(265f, 0.75f, 0.45f)
        }

        private fun trailFor(p: SkillPhase, r: Random): TrailStyle = when {
            p.has(ModulatorKind.SINE_WAVE) || p.has(ModulatorKind.SPIRAL) -> if (r.nextBoolean()) TrailStyle.RIBBON else TrailStyle.GLYPHS
            p.has(ModulatorKind.ACCELERATE) -> if (r.nextBoolean()) TrailStyle.STREAKS else TrailStyle.AFTERIMAGE
            p.has(ModulatorKind.ECHO) || p.has(ModulatorKind.MULTICAST) -> TrailStyle.AFTERIMAGE
            else -> TrailStyle.entries[r.nextInt(TrailStyle.entries.size)]
        }

        private fun impactFor(p: Payload, r: Random): ImpactSignature = impactsFor(p).let { it[r.nextInt(it.size)] }

        private fun decalFor(p: Payload, r: Random): GroundDecal = decalsFor(p).let { it[r.nextInt(it.size)] }

        /** The impacts that suit a payload. */
        fun impactsFor(p: Payload): List<ImpactSignature> =
            when (p) {
                Payload.STAGGER, Payload.KNOCKBACK -> listOf(ImpactSignature.SHOCKWAVE, ImpactSignature.FLASH, ImpactSignature.CRACK_STAR)
                Payload.VACUUM, Payload.PULL -> listOf(ImpactSignature.IMPLOSION, ImpactSignature.GLYPH_STAMP)
                Payload.BURN, Payload.IGNITE_BRUSH -> listOf(ImpactSignature.BLOOM, ImpactSignature.PILLAR, ImpactSignature.FLASH)
                Payload.BRITTLE, Payload.FLASH_FREEZE, Payload.FREEZE_WATER -> listOf(ImpactSignature.CRACK_STAR, ImpactSignature.SPLASH, ImpactSignature.GLYPH_STAMP)
                Payload.CONDUCTIVE -> listOf(ImpactSignature.FLASH, ImpactSignature.PILLAR, ImpactSignature.SHOCKWAVE)
                Payload.CRATER, Payload.RAISE_WALL -> listOf(ImpactSignature.CRACK_STAR, ImpactSignature.SHOCKWAVE, ImpactSignature.PILLAR)
            }

        /** The marks a payload can leave on the ground. */
        fun decalsFor(p: Payload): List<GroundDecal> =
            when (p.element) {
                Element.FIRE -> listOf(GroundDecal.SCORCH, GroundDecal.ASH)
                Element.FROST -> listOf(GroundDecal.RIME, GroundDecal.GLASS)
                Element.EARTH -> listOf(GroundDecal.CRACKS, GroundDecal.RUNE_RING)
                Element.VENOM -> listOf(GroundDecal.POOL, GroundDecal.CRACKS)
                else -> listOf(GroundDecal.RUNE_RING, GroundDecal.NONE, GroundDecal.ASH, GroundDecal.GLASS)
            }

        /** ARGB from hue (degrees, any), saturation and value (0..1). */
        fun hsv(hue: Float, s: Float, v: Float): Int {
            val h = ((hue % 360f) + 360f) % 360f / 60f
            val c = v * s; val x = c * (1f - abs(h % 2f - 1f)); val m = v - c
            val (r, g, b) = when (h.toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
            fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}

/** How the second colour sits against the first. */
enum class Harmony(val label: String, val offset: Float, val saturation: Float, val value: Float) {
    ANALOGOUS("Analogous", 30f, 1f, 0.9f),
    COMPLEMENT("Complementary", 180f, 0.9f, 1f),
    TRIAD("Triadic", 120f, 0.9f, 0.95f),
    SPLIT("Split", 150f, 0.85f, 1f),
    MONO("Monochrome", 0f, 0.5f, 0.6f),
    EMBER_SHIFT("Ember shift", -40f, 1f, 0.75f),
}

/** The attack's body: what flies, flows, stands or circles. Each belongs to one delivery. */
enum class BodyForm(val label: String, val delivery: DeliveryKind) {
    FLAT_BEAM("Flat beam", DeliveryKind.INSTANT_RAY), BRAIDED_BEAM("Braided beam", DeliveryKind.INSTANT_RAY),
    PULSE_BEAM("Pulsing beam", DeliveryKind.INSTANT_RAY), SEGMENT_BEAM("Segmented beam", DeliveryKind.INSTANT_RAY),
    ORB("Orb", DeliveryKind.BALLISTIC), SHARD("Shard", DeliveryKind.BALLISTIC), SPEAR("Spear", DeliveryKind.BALLISTIC),
    STONE("Glyph stone", DeliveryKind.BALLISTIC), SEED("Seed pod", DeliveryKind.BALLISTIC),
    RIPPLE("Ripple", DeliveryKind.SURFACE_WAVE), SERPENT("Serpent", DeliveryKind.SURFACE_WAVE), ROOTS("Roots", DeliveryKind.SURFACE_WAVE),
    SPIKES("Spike run", DeliveryKind.SURFACE_WAVE),
    RUNE_CIRCLE("Rune circle", DeliveryKind.IMPACT_FIELD), PILLAR_FIELD("Pillars", DeliveryKind.IMPACT_FIELD),
    VORTEX("Vortex", DeliveryKind.IMPACT_FIELD), TOTEM("Totem", DeliveryKind.IMPACT_FIELD),
    CHAIN_LINK("Chain", DeliveryKind.TETHER), THREAD("Thread", DeliveryKind.TETHER), ARC("Arc", DeliveryKind.TETHER),
    GLYPH_ROPE("Glyph rope", DeliveryKind.TETHER),
    WISPS("Wisps", DeliveryKind.ORBITAL), BLADES("Blades", DeliveryKind.ORBITAL), MOONS("Moons", DeliveryKind.ORBITAL),
    MASKLETS("Masklets", DeliveryKind.ORBITAL),
}

/**
 * A glyph stamped through an attack's decals, particles and trail. Geometric
 * marks of the kind carved, woven and painted across the continent's art,
 * named for their shapes rather than claiming any one symbol's meaning.
 */
enum class Motif(val label: String) {
    CHEVRON("Chevron"), SPIRAL("Spiral"), LATTICE("Lattice"), COMB("Comb"), CRESCENT("Crescent"), DIAMOND("Diamond"),
    ZIGZAG("Zigzag"), CONCENTRIC("Concentric"), CROSS("Cross"), EYE("Eye"), SUNBURST("Sunburst"), LADDER("Ladder"),
    KNOT("Knot"), NESTED_SQUARES("Nested squares"), WAVE("Wave"), TRIANGLES("Triangles"), DOTS("Dots"), FAN("Fan"),
    STAR("Star"), HOOK("Hook"), ARROW("Arrow"), DOT_RING("Dot ring"), BRAID("Braid"), SCALES("Scales"),
}

enum class TrailStyle(val label: String) {
    NONE("Clean"), RIBBON("Ribbon"), SPARKS("Sparks"), SMOKE("Smoke"), SHARDS("Shards"), GLYPHS("Glyph"),
    AFTERIMAGE("Afterimage"), DRIP("Dripping"), EMBERS("Ember"), STREAKS("Streaking"),
}

enum class ParticleShape(val label: String) {
    DOT("Dots"), STREAK("Streaks"), VOXEL("Voxels"), TRIANGLE("Triangles"), RING("Rings"), STAR("Stars"),
    FEATHER("Feathers"), LEAF("Leaves"), CROSS("Crosses"), DROPLET("Droplets"),
}

enum class Rhythm(val label: String) { STEADY("Steady"), PULSE("Pulsing"), BURST("Bursting"), STUTTER("Stuttering"), CRESCENDO("Swelling"), HEARTBEAT("Heartbeat") }

enum class Flourish(val label: String) { STRAIGHT("Straight"), WOBBLE("Wobbling"), TWIST("Twisting"), FLICKER("Flickering"), BREATHE("Breathing"), SPIN("Spinning") }

enum class ImpactSignature(val label: String) {
    FLASH("Flash"), SHOCKWAVE("Shockwave ring"), PILLAR("Pillar"), BLOOM("Bloom"), CRACK_STAR("Crack star"),
    IMPLOSION("Implosion"), SPLASH("Splash"), GLYPH_STAMP("Glyph stamp"),
}

enum class GroundDecal(val label: String) { NONE("None"), SCORCH("Scorch"), RIME("Rime"), CRACKS("Cracks"), RUNE_RING("Rune ring"), POOL("Pool"), ASH("Ash"), GLASS("Glass") }
