package com.stratum.core.domain.art

/**
 * Everything a world looks like, as one value.
 *
 * The renderer used to hold this knowledge as constants: a depth shade
 * strength, a ledge shadow alpha, a player ring colour. Constants cannot be
 * asked for by a player, cannot vary per biome and cannot be generated, so the
 * game had exactly one look and it was whichever one was last committed.
 *
 * Here the look is data. A sentence from the player produces one of these
 * ([StyleLexicon]), so does a model ([StyleBrief]), so does a content pack, and
 * the renderer draws whichever it is handed without knowing which it was.
 *
 * Nothing in here says *what is in the world* — that is the content pack's job
 * and it stays a voxel simulation either way. This says only how the same world
 * is lit, shaded, weighted and framed.
 */
data class ArtDirection(
    val id: String,
    val name: String,
    /** One sentence, shown to the player and handed to image models as context. */
    val summary: String = "",
    val palette: StylePalette = StylePalette(),
    val light: LightingRule = LightingRule(),
    val contrast: ContrastContract = ContrastContract(),
    val shape: ShapeLanguage = ShapeLanguage(),
    val atmosphere: AtmosphereRule = AtmosphereRule(),
    /** Words appended to every asset prompt so generated art matches the world. */
    val diction: StyleDiction = StyleDiction(),
    /**
     * Varies the derived look without changing the style.
     *
     * Two players who both ask for "dark" should not get the identical world,
     * and the same player asking twice should be able to reroll. Every
     * derivation that jitters reads this, so a seed is the whole of "same
     * words, different world" — and holding it fixed is the whole of
     * reproducibility.
     */
    val seed: Long = 0L,
) {
    /** Reroll: same words, different world. */
    fun reseeded(seed: Long): ArtDirection = copy(seed = seed)

    /**
     * Pulls a style back inside the bounds that keep the game playable.
     *
     * Every source of a style is untrusted in the same way: a player typing a
     * mood, a model answering a brief, a pack shipping a look. None of them are
     * trying to break anything, and all of them can — ground bright enough to
     * swallow the monsters, an outline of nothing, terrain more colourful than
     * the loot. The caps are not taste, they are the floor under which the
     * screen stops being readable, and they are applied to every style before
     * it ever reaches the renderer.
     *
     * Deliberately generous. A style should be able to be garish, washed out,
     * nearly black or nearly white; it should not be able to hide the thing the
     * player is about to be killed by.
     */
    fun enforcePlayable(): ArtDirection {
        val floor = contrast.terrainValueFloor.coerceIn(0f, MAX_TERRAIN_FLOOR)
        val ceiling = contrast.terrainValueCeiling
            .coerceIn(floor + MIN_TERRAIN_BAND, MAX_TERRAIN_CEILING)
        return copy(
            contrast = contrast.copy(
                terrainSaturation = contrast.terrainSaturation.coerceIn(0f, MAX_TERRAIN_SATURATION),
                terrainValueFloor = floor,
                terrainValueCeiling = ceiling,
                // Whatever the mood, a thing that can hurt you is allowed to be
                // more colourful than the ground it is standing on.
                featureSaturation = contrast.featureSaturation
                    .coerceAtLeast(contrast.terrainSaturation + MIN_FEATURE_LEAD),
                outlineWidth = contrast.outlineWidth.coerceIn(MIN_OUTLINE, MAX_OUTLINE),
                heroScale = contrast.heroScale.coerceIn(MIN_HERO_SCALE, MAX_HERO_SCALE),
            ),
            light = light.copy(
                // A world with no ambient light at all is a black rectangle.
                ambientStrength = light.ambientStrength.coerceIn(MIN_AMBIENT, 1f),
                depthFalloff = light.depthFalloff.coerceIn(0f, MAX_DEPTH_FALLOFF),
            ),
            atmosphere = atmosphere.copy(
                hazeStrength = atmosphere.hazeStrength.coerceIn(0f, MAX_HAZE),
                vignette = atmosphere.vignette.coerceIn(0f, MAX_VIGNETTE),
                moteDensity = atmosphere.moteDensity.coerceIn(0, MAX_MOTES),
            ),
        )
    }

    companion object {
        /**
         * How bright the ground is allowed to get.
         *
         * The number that does the most work in the whole file. Above it, pale
         * terrain starts competing with the rim light that separates actors
         * from it, and a bright style stops being bright and starts being
         * unreadable.
         */
        const val MAX_TERRAIN_CEILING = 0.85f
        const val MAX_TERRAIN_FLOOR = 0.6f
        const val MIN_TERRAIN_BAND = 0.15f
        const val MAX_TERRAIN_SATURATION = 1.1f
        /** How much more colourful a threat or a prize must be than the floor. */
        const val MIN_FEATURE_LEAD = 0.25f
        const val MIN_OUTLINE = 1f
        const val MAX_OUTLINE = 8f
        const val MIN_HERO_SCALE = 1f
        const val MAX_HERO_SCALE = 3f
        const val MIN_AMBIENT = 0.12f
        const val MAX_DEPTH_FALLOFF = 0.85f
        const val MAX_HAZE = 0.8f
        const val MAX_VIGNETTE = 0.8f
        const val MAX_MOTES = 160

        /**
         * The house style: readable voxels, warm key light, cool shadow.
         *
         * Deliberately the least opinionated thing that still has a point of
         * view. Every other style in the lexicon is a set of edits to this, so
         * a trait that goes wrong degrades to something playable rather than to
         * something unrecognisable.
         */
        val HOUSE = ArtDirection(
            id = "stratum:house",
            name = "Stratum House Style",
            summary = "Chunky isometric voxels, warm key light from the upper left, " +
                "deep cool shadow, actors held above the terrain by contrast.",
        )
    }
}

/**
 * The colours a style thinks in.
 *
 * Not the colours of anything in particular: a pack still owns its own block
 * colours, and overriding those would make every world the same world wearing a
 * different hat. These are the operations applied *to* whatever colours the
 * pack shipped — what the sun does to them, what the shadows do to them, and
 * which few accents are allowed to shout.
 */
data class StylePalette(
    /** Key light colour, multiplied into lit surfaces. */
    val sun: Long = 0xFFFFF2D4,
    /** What unlit faces are tinted towards. Cool by default; warm reads as dusk. */
    val shadow: Long = 0xFF2A3348,
    /** Colour of the light that reaches everything, however shaded. */
    val ambient: Long = 0xFF5A6478,
    /** Distance haze and the colour the world dissolves into. */
    val fog: Long = 0xFF161A26,
    /** Outlines and contact shadows. Rarely pure black — that reads as a hole. */
    val ink: Long = 0xFF14110E,
    /** Reserved for things that want to hurt you. Never used on terrain. */
    val hostile: Long = 0xFFD2544B,
    /** Reserved for shrines, landmarks and anything the world holds sacred. */
    val sacred: Long = 0xFFCD7F32,
    /** Reserved for ore, loot and anything worth walking towards. */
    val resource: Long = 0xFFF0C862,
    /** The rim that separates the player from whatever they are standing on. */
    val heroRim: Long = 0xFF7FD4E0,
    /**
     * Ramp that terrain colours snap to, or empty for continuous shading.
     *
     * A short ramp is the cheapest way to get an illustrated look out of
     * procedural colour: it collapses the thousand slightly different greens a
     * generator produces into four deliberate ones.
     */
    val ramp: List<Long> = emptyList(),
) {
    init {
        require(ramp.size <= MAX_RAMP) { "A ramp of ${ramp.size} colours is a photograph, not a palette" }
    }

    private companion object {
        const val MAX_RAMP = 32
    }
}

/**
 * The fake three-dimensional lighting model.
 *
 * Real global illumination on a phone, for a world of tens of thousands of
 * cubes, is not on the table. None of it is needed: an isometric scene reads as
 * lit if the three faces of a cube differ, if lower ground is darker, if ledges
 * cast, and if emissive things bleed onto their neighbours. All four are
 * lookups.
 */
data class LightingRule(
    /**
     * Which side the key light comes from, in degrees clockwise from screen up.
     *
     * 315 is the upper left, which is where every illustrator puts it and where
     * the eye expects it. Moving it is how a style says "underground" or
     * "sunset" without changing a single colour.
     */
    val sunAzimuth: Float = 315f,
    /** How much brighter a fully lit face is than an ambient one. */
    val sunStrength: Float = 0.42f,
    /** The floor: how much light a face pointing away from the sun still gets. */
    val ambientStrength: Float = 0.58f,
    /** How far a face is tinted towards [StylePalette.shadow] when unlit. */
    val shadowTint: Float = 0.35f,
    /** Levels below the camera over which ground fades to its darkest. */
    val depthRange: Int = 8,
    /** How dark the bottom of that range gets, as a share of the surface value. */
    val depthFalloff: Float = 0.45f,
    /** Darkening applied to a cell a taller neighbour casts onto. */
    val ledgeOcclusion: Float = 0.22f,
    /** Extra darkening in the inside corner where two raised neighbours meet. */
    val cornerOcclusion: Float = 0.12f,
    /** How far an emissive block bleeds onto what is around it, in blocks. */
    val emissiveRadius: Float = 3.5f,
    /** How strongly it bleeds at the source. */
    val emissiveStrength: Float = 0.8f,
) {
    init {
        require(depthRange >= 1) { "A depth range below one level cannot shade anything" }
    }

    /**
     * How lit a face pointing in a screen direction is, 0..1.
     *
     * [facing] is degrees clockwise from screen up, matching [sunAzimuth], so a
     * cube's left and right faces are two constants and the top face is a
     * third. The curve is a cosine lobe rather than a step, because a hard
     * terminator on a cube face makes the whole field look like a chequerboard.
     */
    fun exposure(facing: Float): Float {
        val delta = angleBetween(facing, sunAzimuth)
        val lobe = ((180f - delta) / 180f).coerceIn(0f, 1f)
        val eased = lobe * lobe * (3f - 2f * lobe)
        return (ambientStrength + sunStrength * eased).coerceIn(0f, 2f)
    }

    private fun angleBetween(a: Float, b: Float): Float {
        val raw = ((a - b) % 360f + 360f) % 360f
        return if (raw > 180f) 360f - raw else raw
    }
}

/**
 * Who is allowed to be loud.
 *
 * The rule the screenshot breaks is this one: the grass, the trees, the ore and
 * the monsters all sit in the same band of colour and the same band of
 * brightness, so the eye has nothing to land on and the scene reads as a
 * pleasant prototype. Contrast is a budget. Terrain is charged for it and
 * actors are paid it, and the numbers here are that transaction.
 */
data class ContrastContract(
    /** Multiplier on terrain colourfulness. Below one, deliberately. */
    val terrainSaturation: Float = 0.72f,
    /** Terrain brightness is squeezed into this band so nothing on the floor shouts. */
    val terrainValueFloor: Float = 0.10f,
    val terrainValueCeiling: Float = 0.68f,
    /** Multiplier on the colourfulness of ore, loot and anything interactable. */
    val featureSaturation: Float = 1.35f,
    /**
     * How much bigger an actor is drawn than the grid says it is.
     *
     * A person who is literally one block wide is a speck on a field of blocks,
     * and a speck cannot be cared about or reacted to. Every isometric game
     * that reads well cheats this, and states the cheat.
     */
    val heroScale: Float = 1.6f,
    /** Same, for anything hostile. Slightly under the hero: you are the subject. */
    val enemyScale: Float = 1.35f,
    /** Width of the dark contour that holds an actor against any background. */
    val outlineWidth: Float = 3f,
    /** How far the outline colour is pushed towards the ink. 1 is flat black. */
    val outlineDarkness: Float = 0.82f,
    /** Strength of the rim that separates an actor's top edge from the terrain. */
    val rimStrength: Float = 0.55f,
    /** Opacity of the ellipse that plants an actor on the ground. */
    val contactShadow: Float = 0.5f,
    /** How much the contact shadow spreads as an actor rises off the floor. */
    val contactSpread: Float = 0.35f,
    /** Opacity of the halo under things worth walking towards. */
    val interactableHalo: Float = 0.35f,
) {
    /**
     * The band terrain brightness is squeezed into, never inverted.
     *
     * Normalised rather than validated, and that is not defensive coding: it is
     * what lets traits compose. A style that raises the floor and a style that
     * lowers the ceiling are each perfectly reasonable, and asking for both at
     * once — "kawaii neon" — used to throw before anything had a chance to
     * clamp it. Refusing a combination of two valid requests is a worse answer
     * than honouring it as far as it can be honoured.
     */
    val terrainValueBand: ClosedFloatingPointRange<Float>
        get() = terrainValueFloor..maxOf(terrainValueCeiling, terrainValueFloor + ArtDirection.MIN_TERRAIN_BAND)
}

/**
 * The forms the world is built out of.
 *
 * Shape language is the half of art direction that survives being redrawn. A
 * style that is only a palette becomes a different style the moment somebody
 * generates a new pack; a style that says "broad planes, tapered trunks, heavy
 * contours" keeps its identity through any amount of new content.
 */
data class ShapeLanguage(
    /** Per-cell brightness jitter, so a plain of one block is not one flat sheet. */
    val grain: Float = 0.07f,
    /** Opacity of the seam drawn on every top face, which is what makes cells cells. */
    val seam: Float = 0.10f,
    /** How strongly a terrace edge is picked out. The readable-cliff knob. */
    val terraceEmphasis: Float = 1f,
    /**
     * Base size of a prop relative to a tile.
     *
     * The silhouettes are drawn in tile units — a canopy is about three
     * quarters of a tile across — so one means "the size it was designed at".
     * Under one because packs state their own per-block scale on top of this,
     * and they set it for glyphs: the pack's own canopy asks for nearly twice
     * size, which at a base of one drew a forest whose trees hid the forest.
     */
    val propScale: Float = 0.65f,
    /**
     * How much props vary in size and form between instances.
     *
     * Repetition is the tell that a landscape was generated: the screenshot's
     * trees are the same tree, so the eye reads wallpaper instead of woodland.
     * Three silhouettes with jitter reads as a forest; one silhouette repeated
     * two hundred times reads as a texture, however good the one is.
     */
    val propVariance: Float = 0.3f,
    /** Whether props are drawn as vector silhouettes rather than glyphs. */
    val silhouetteProps: Boolean = true,
    /**
     * Snap terrain to the region's own materials when the style names no ramp
     * of its own.
     *
     * Off by default, and the default is the interesting part. A region's ramp
     * is built from the handful of blocks it is made of, at a couple of values
     * each — and once the lighting model darkens a lit green, the nearest entry
     * in a ramp that also holds granite is the granite. Snapping every frame to
     * it turned a forest floor grey. A style that wants flat colour says so and
     * brings its own ramp; one that wants the region's own few colours turns
     * this on and accepts the trade.
     */
    val quantizeToRegion: Boolean = false,
    /** Width of the contour around a prop silhouette. */
    val propOutline: Float = 2f,
)

/**
 * The air between the camera and the world.
 *
 * Nothing here is necessary to see the world, which is exactly why it is worth
 * having: haze, motes and a vignette are the cheapest possible signal that a
 * place has weather, depth and a mood, and none of them cost a single extra
 * block of simulation.
 */
data class AtmosphereRule(
    /** Colour the world dissolves into at the edge of sight. */
    val hazeColor: Long = 0xFF161A26,
    /** How much haze the furthest visible ground takes. */
    val hazeStrength: Float = 0.22f,
    /** Darkening at the corners of the screen. */
    val vignette: Float = 0.28f,
    /** Wash laid over the whole frame, e.g. moonlight or firelight. */
    val overlay: Long = 0x00000000,
    val moteKind: MoteKind = MoteKind.NONE,
    /** Roughly how many motes are in view at once. */
    val moteDensity: Int = 0,
    val moteColor: Long = 0xFFF0C862,
    /** How fast motes drift, in tiles per second. */
    val moteDrift: Float = 0.35f,
    /** Sky behind the world, top and bottom of a vertical wash. */
    val skyTop: Long = 0xFF0D1018,
    val skyBottom: Long = 0xFF1B2030,
) {
    init {
        require(moteDensity >= 0) { "A negative mote count is not a weather system" }
    }
}

/**
 * What drifts through the air.
 *
 * A closed set rather than free text because the renderer has to draw each of
 * them, and a style that asks for something unknown should fall back to nothing
 * rather than fail to render. A generated style names one of these; the words
 * that got it there can be anything.
 */
enum class MoteKind {
    NONE,
    FIREFLIES,
    SPORES,
    MIST,
    ASH,
    EMBERS,
    SNOW,
    RAIN,
    LEAVES,
    DUST,
    PETALS,
    SPARKS,
}

/**
 * The words handed to image models, so generated art belongs to this world.
 *
 * Kept next to the numbers on purpose. A style whose prompt words and whose
 * rendering rules can drift apart will drift apart, and then generated props
 * stop matching the terrain they stand on — which is the failure that makes
 * AI-assisted art look bolted on rather than art directed.
 */
data class StyleDiction(
    /** The unchanging half of every prompt. See [ArtBible]. */
    val house: String = "chunky isometric voxel action RPG asset, fixed 2:1 isometric camera, " +
        "stylized hand-painted low-poly surfaces, large readable planes, " +
        "dark clean contour lines, deliberate silhouette",
    /** Style-specific words, e.g. "grimdark", "pastel kawaii", "thick impasto". */
    val flavour: String = "",
    /** Words the model must avoid, which does more work than any positive phrase. */
    val forbidden: String = "no text, no UI, no watermark, no photographic detail, " +
        "no smooth plastic 3D render, no busy noise texture",
)
