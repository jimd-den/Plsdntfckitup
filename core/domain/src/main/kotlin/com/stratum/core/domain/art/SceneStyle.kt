package com.stratum.core.domain.art

import com.stratum.core.domain.world.BlockType

/**
 * What a surface is made of, before any light touches it.
 *
 * The 2D renderer had to bake lighting into every colour because it had no
 * lights, only fill colours. A 3D renderer has real lights, so the director's
 * job splits in two: say what a surface *is* here, say what the light *does*
 * in [SceneLighting], and let the shader combine them. Baking light into the
 * albedo as well would light everything twice.
 */
data class SurfaceStyle(
    /** Base colour after the contrast contract, with no light applied. */
    val albedo: Long,
    /**
     * A texture to multiply the albedo by, by key, or null for flat colour.
     *
     * A key rather than an image because the director does not own images:
     * the asset forge does, and a missing texture must degrade to the flat
     * colour rather than to a pink checkerboard.
     */
    val texture: String? = null,
    /** 0..1: how much this surface glows on its own. */
    val emissive: Float = 0f,
    val emissiveColor: Long = 0x00000000,
)

/** Which face of a block a surface is for, since tops and sides differ. */
enum class SurfaceFace { TOP, SIDE }

/**
 * Everything the light is doing in one frame.
 *
 * Deliberately the whole lighting model in one small record, so two backends —
 * a GPU shader on a phone and a software rasteriser on a build machine — can
 * be held to exactly the same numbers. Anything that is not in here is not
 * lighting, it is a bug in one of the backends.
 */
data class SceneLighting(
    /** Unit vector pointing *towards* the sun, in world space (z up). */
    val sunX: Float,
    val sunY: Float,
    val sunZ: Float,
    val sunColor: Long,
    val sunIntensity: Float,
    /** Hemisphere ambient: what faces pointing up and down receive. */
    val skyAmbient: Long,
    val groundAmbient: Long,
    val ambientIntensity: Float,
    /** 0 is no shadow, 1 is pitch black under an overhang. */
    val shadowStrength: Float,
    val fogColor: Long,
    /**
     * Fog starts this far beyond the point the camera looks at, in blocks, and
     * is total at [fogEnd].
     *
     * Measured from the focus, not the lens. The camera sits thirty-odd blocks
     * back, so fog measured from the eye starts before the first thing on
     * screen: the whole view came back half-fogged, which read as pink soup in
     * a pastel style and as a black screen in a dark one.
     */
    val fogStart: Float,
    val fogEnd: Float,
    /** Height fog: extra haze below this world z, which is what makes pits read as deep. */
    val fogFloor: Float,
    val rimColor: Long,
    val rimStrength: Float,
    val exposure: Float,
    /** Final colourfulness, applied after lighting. Grimdark lives here. */
    val saturation: Float,
    val vignette: Float,
    val skyTop: Long,
    val skyBottom: Long,
    /** Scales every point light, so a style can say "torches matter" or "they don't". */
    val pointLightGain: Float,
    /**
     * The light the hero carries, 0 for none.
     *
     * Diablo's light radius, and for the same reason: a dark style is only
     * playable if the player can see what is next to them, and a pool of warm
     * light that travels with the hero keeps the darkness everywhere else.
     */
    val heroLight: Float = 0f,
    val heroLightRadius: Float = 7f,
    /**
     * Unit vector towards the fill light, which the scene sets from the camera.
     *
     * The fill sits on the camera's side, as in any three-point setup, so that
     * every face the player can see receives some light. Placed opposite the
     * sun instead, faces turned neither to the sun nor to the fill — every
     * south-facing step on a west-lit map — drew as black strips.
     */
    val fillX: Float = 0f,
    val fillY: Float = 0f,
    val fillZ: Float = 1f,
    val fillStrength: Float = 0.32f,
    /**
     * Share of a painted floor's contrast that survives; the rest is pulled
     * towards the painting's own average colour.
     *
     * The floor is background. In Diablo and Hades it is the quietest thing on
     * screen, so that characters, monsters, loot and spell effects — the things
     * a player must read in a fraction of a second — stand out against it. An
     * image model paints every tile as if it were the subject, and a field of
     * those at full strength buries everything standing on it.
     */
    val floorDetail: Float = 0.45f,
    /** Saturation of painted floors relative to the painting, for the same reason. */
    val floorSaturation: Float = 0.75f,
)

/**
 * The half of a director a 3D renderer needs.
 *
 * Separate from [WorldArtDirector] so the 2D path keeps working untouched and
 * so a director that only knows how to do one of the two is still a director.
 */
interface SceneArtDirector {
    fun surfaceFor(block: BlockType, face: SurfaceFace, biomeId: String?): SurfaceStyle

    fun lightingFor(biomeId: String?, time: WorldTime): SceneLighting
}
