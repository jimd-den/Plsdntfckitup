package com.stratum.core.domain.attack

/**
 * The orthogonal parts every procedural attack is assembled from.
 *
 * An attack is not a named ability (a fireball, a cleave) but a pipeline of
 * independent layers, each chosen on its own:
 *
 * ```
 * intent -> [Delivery] -> [Emitter] -> [Modulators] -> [Payload] -> (sub-triggers)
 * ```
 *
 * - the [Delivery] says how its energy travels;
 * - the [Emitter] how it is spread through space;
 * - the [Modulator]s bend, split, repeat or delay it;
 * - the [Payload] says what it does where it lands, to bodies and to the
 *   voxels underneath;
 * - [SkillEvent]s chain further phases off its hits, crits, kills and the
 *   blocks it strikes.
 *
 * Every numeric parameter moves in fixed steps ([Steps]), so an attack can be
 * written down exactly (see [AttackCode]) and the space of them counted.
 */
object AttackGrammar {
    /** How finely each continuous parameter is stepped. */
    const val STEPS = 8

    /** At most this many modulators in one phase: enough to combine, few enough to read. */
    const val MAX_MODULATORS = 3

    /** Phases chained off phases, at most this deep: the trigger engine's own depth guard agrees. */
    const val MAX_DEPTH = 3

    /** At most this many sub-triggers on one phase. */
    const val MAX_SUB_TRIGGERS = 2
}

/** A step 0 until [AttackGrammar.STEPS], read as a value in [lo]..[hi]. */
internal fun stepped(step: Int, lo: Float, hi: Float): Float =
    lo + (hi - lo) * step.coerceIn(0, AttackGrammar.STEPS - 1) / (AttackGrammar.STEPS - 1f)

/**
 * How an attack's energy moves from the caster to where it acts. Each has
 * two stepped parameters, [a] and [b], read in its own terms.
 */
enum class DeliveryKind(val label: String, val verb: String, val aLabel: String, val bLabel: String) {
    /** A raycast that lands the instant it is cast. */
    INSTANT_RAY("Instant ray", "strikes instantly along a line", "Range", "Beam width"),

    /** A projectile thrown on an arc, pulled down by gravity. */
    BALLISTIC("Ballistic arc", "arcs through the air", "Launch speed", "Gravity"),

    /** Energy that hugs the ground, flowing over every step of the voxel surface. */
    SURFACE_WAVE("Surface wave", "crawls along the ground's contours", "Speed", "Climb height"),

    /** A standing zone or rune that holds its ground. */
    IMPACT_FIELD("Impact field", "settles as a field on the ground", "Radius", "Duration"),

    /** A beam held between caster and target while it channels. */
    TETHER("Tether", "binds caster and target with a channelled beam", "Range", "Channel time"),

    /** Shields or wisps circling the caster, flung out or striking what comes near. */
    ORBITAL("Orbital swarm", "circles the caster as an orbiting swarm", "Orbit radius", "Spin speed"),
}

/** A delivery with its two parameters, each a step in its own range. */
data class Delivery(val kind: DeliveryKind, val a: Int = 3, val b: Int = 3) {
    init { require(a in 0 until AttackGrammar.STEPS && b in 0 until AttackGrammar.STEPS) { "delivery steps out of range" } }

    /** Blocks the delivery reaches: a ray's range, a throw's distance, a field's or orbit's radius. */
    val reach: Float get() = when (kind) {
        DeliveryKind.INSTANT_RAY, DeliveryKind.TETHER -> stepped(a, 5f, 16f)
        DeliveryKind.BALLISTIC -> stepped(a, 6f, 18f)
        DeliveryKind.SURFACE_WAVE -> stepped(a, 5f, 14f)
        DeliveryKind.IMPACT_FIELD -> stepped(a, 1.5f, 5f)
        DeliveryKind.ORBITAL -> stepped(a, 1.2f, 3.5f)
    }

    /** Blocks per second for anything that travels. */
    val speed: Float get() = when (kind) {
        DeliveryKind.BALLISTIC -> stepped(a, 8f, 22f)
        DeliveryKind.SURFACE_WAVE -> stepped(a, 5f, 14f)
        DeliveryKind.ORBITAL -> stepped(b, 1.5f, 7f)
        else -> 30f
    }

    /** Seconds it lasts: a field, a channel, an orbit. */
    val duration: Float get() = when (kind) {
        DeliveryKind.IMPACT_FIELD -> stepped(b, 1.5f, 8f)
        DeliveryKind.TETHER -> stepped(b, 0.8f, 4f)
        DeliveryKind.ORBITAL -> stepped(a, 3f, 8f)
        else -> 0f
    }
}

/** How a delivery is spread through space. */
enum class EmitterShape(val label: String, val phrase: String) {
    SINGLE("Single spike", "at a single target"),
    PIERCING_LINE("Piercing line", "in a piercing line"),
    NOVA("Concentric nova", "in an expanding ring"),
    CONE("Cleaving cone", "across a cleaving arc"),
    FAN("Forking fan", "as a fan of shots"),
    HELIX("Helix", "in a twisting helix"),
    CHAIN("Chain leap", "leaping between nearby foes"),
    RUPTURE_GRID("Rupture grid", "erupting from the ground in a pattern"),
}

/** An emitter: its shape, how many it makes, and how wide it spreads. */
data class Emitter(val shape: EmitterShape, val count: Int = 1, val spread: Int = 3) {
    init {
        require(count in 1..MAX_COUNT) { "an emitter makes 1..$MAX_COUNT, not $count" }
        require(spread in 0 until AttackGrammar.STEPS) { "emitter spread out of range" }
    }

    /** Degrees across a cone or fan; a helix's turns, a grid's pitch, a chain's hop, read from the same step. */
    val spreadDegrees: Float get() = stepped(spread, 20f, 180f)

    companion object { const val MAX_COUNT = 8 }
}

/** The elements an attack carries: its damage, its colours and its sound. */
enum class Element(val label: String, val adjective: String, val noun: String) {
    PHYSICAL("Physical", "Iron", "Blade"),
    FIRE("Fire", "Burning", "Flame"),
    FROST("Frost", "Frozen", "Rime"),
    STORM("Storm", "Thunder", "Lightning"),
    VENOM("Venom", "Venomous", "Venom"),
    SPIRIT("Spirit", "Ancestral", "Spirit"),
    EARTH("Earth", "Earthen", "Stone"),
    SHADOW("Shadow", "Shadowed", "Night"),
}

/** Which family a payload belongs to. */
enum class PayloadFamily { KINETIC, AFFLICTION, VOXEL }

/** What an attack does where it lands: to bodies, and to the blocks beneath them. */
enum class Payload(val label: String, val family: PayloadFamily, val element: Element, val phrase: String) {
    // Kinetic: moves or staggers what it hits.
    STAGGER("Stagger", PayloadFamily.KINETIC, Element.PHYSICAL, "staggers what it hits"),
    KNOCKBACK("Knockback", PayloadFamily.KINETIC, Element.PHYSICAL, "hurls targets back"),
    VACUUM("Micro-vacuum", PayloadFamily.KINETIC, Element.SHADOW, "collapses into a vacuum that sucks targets in"),
    PULL("Pull", PayloadFamily.KINETIC, Element.SPIRIT, "drags targets toward the caster"),

    // Affliction: what lingers on a body.
    BURN("Burn", PayloadFamily.AFFLICTION, Element.FIRE, "sets targets burning"),
    BRITTLE("Brittle", PayloadFamily.AFFLICTION, Element.FROST, "leaves targets brittle, taking more damage"),
    CONDUCTIVE("Conductive shock", PayloadFamily.AFFLICTION, Element.STORM, "charges targets with conductive shock"),
    FLASH_FREEZE("Flash-freeze", PayloadFamily.AFFLICTION, Element.FROST, "flash-freezes targets in place"),

    // Voxel: what it does to the world.
    CRATER("Crater", PayloadFamily.VOXEL, Element.EARTH, "blasts a crater into the ground"),
    RAISE_WALL("Raise wall", PayloadFamily.VOXEL, Element.EARTH, "raises a temporary wall of earth"),
    IGNITE_BRUSH("Ignite brush", PayloadFamily.VOXEL, Element.FIRE, "sets the brush and grass ablaze"),
    FREEZE_WATER("Freeze water", PayloadFamily.VOXEL, Element.FROST, "freezes water into walkable ice"),
}

/**
 * A modulator's kind: a mathematical combinator on the attack's flight,
 * spread or timing. Each reads one stepped [Modulator.level].
 */
enum class ModulatorKind(val label: String, val adjective: String, val phrase: (Int) -> String) {
    FORK("Fork", "Forked", { "branches into ${2 + it / 2} on first contact" }),
    PIERCE("Pierce", "Piercing", { "passes through ${1 + it} foes, losing ${10 + it * 2}% each" }),
    BLOOM("Bloom", "Blooming", { "bursts into ${2 + it / 2} smaller shells as it ends" }),
    SPLIT("Split", "Splitting", { "splits into ${2 + it / 3} when it strikes" }),
    ACCELERATE("Accelerate", "Quickening", { "speeds up as it flies" }),
    SINE_WAVE("Sine wave", "Weaving", { "weaves side to side as it travels" }),
    HOMING("Homing", "Seeking", { "turns to seek its target" }),
    RICOCHET("Ricochet", "Ricocheting", { "ricochets off walls ${1 + it / 2} times" }),
    BOOMERANG("Boomerang", "Returning", { "returns to the caster" }),
    GRAVITATE("Gravitate", "Gravitic", { "draws nearby foes toward its path" }),
    ECHO("Echo", "Echoing", { "echoes at the target after ${"%.1f".format(0.4f + it * 0.1f)}s" }),
    DELAY("Delay", "Delayed", { "lands after a ${"%.1f".format(0.3f + it * 0.15f)}s delay" }),
    MULTICAST("Multicast", "Repeating", { "repeats ${2 + it / 3} times" }),
    SPIRAL("Spiral", "Spiralling", { "spirals outward" }),
    LINGER("Linger", "Lingering", { "leaves a lingering patch behind" }),
    SIPHON("Siphon", "Siphoning", { "siphons ${2 + it}% of damage as life" }),
    SHATTER("Shatter", "Shattering", { "shatters into fragments on a kill" }),
    VOXEL_DISRUPTION("Voxel disruption", "Quaking", { "craters the ground in a ${"%.1f".format(0.8f + it * 0.25f)}-block radius" }),
}

/** A modulator with its stepped strength. */
data class Modulator(val kind: ModulatorKind, val level: Int = 3) {
    init { require(level in 0 until AttackGrammar.STEPS) { "modulator level out of range" } }

    val phrase: String get() = kind.phrase(level)
}

/** The moments a phase can launch a further phase on. */
enum class SkillEvent(val label: String, val phrase: String) {
    ON_CAST("On cast", "as it is cast"),
    ON_HIT("On hit", "on each hit"),
    ON_CRIT("On crit", "on a critical hit"),
    ON_KILL("On kill", "on a kill"),
    ON_BLOCK("On block", "when it is blocked"),
    ON_DASH("On dash", "as the caster dashes"),
    ON_VOXEL_HIT("On voxel hit", "where it strikes the ground or a wall"),
    ON_EXPIRE("On expire", "as it ends"),
}
