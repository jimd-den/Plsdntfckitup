package com.stratum.engine.scene.quality

/**
 * The handcrafted-miniature finish, one switch per ingredient.
 *
 * The look being chased is a warm, painterly table-top model built from
 * thousands of tiny cubes: crevices that go soft and dark, cube edges that
 * catch the sun, no two voxels quite the same colour, grass that is tufts
 * rather than a green lid, distance that goes hazy and warm, and a town whose
 * windows glow when the sun is down. Each ingredient here is cheap on its own
 * and each is a separate field, so a tier can buy exactly the ones it can
 * afford and a player (or a bug hunt) can turn any one off.
 *
 * Every number is read by both backends -- the GLES shaders and the
 * software rasteriser behind the preview images -- through
 * [com.stratum.engine.scene.ShadingModel], so the screenshots stay evidence.
 * [OFF] reproduces the renderer as it was before any of this existed.
 */
data class DioramaLook(
    /**
     * Per-voxel colour grain, as a share of brightness (0 off). Greedy
     * meshing merges a whole field of grass into one quad of one colour,
     * which is exactly what reads as plastic; a hash of the voxel's own cell
     * gives each tiny cube its own tone again, at no geometry cost.
     */
    val grain: Float = 0f,
    /**
     * How strongly voxel edges are rounded in the lighting (0 off). The
     * normal is tipped outwards near each cube's edges, so every microvoxel
     * catches a sliver of sun on one side and falls away on the other -- the
     * bevelled-cube read, faked per pixel on flat quads.
     */
    val bevel: Float = 0f,
    /**
     * Deepens the mesher's corner occlusion (0 leaves it as meshed, 1 about
     * doubles its reach) and lets part of it shade direct light too, so
     * crevices go properly soft and dark instead of merely a little grey.
     */
    val occlusionDepth: Float = 0f,
    /**
     * Aerial perspective: distance and low ground take on a haze that is
     * warm towards the sun and cool away from it, on top of the fog that
     * hides the world's edge. Depth reads as depth, the way it does in a
     * painted backdrop.
     */
    val aerialHaze: Boolean = false,
    /**
     * Emissive gain added at full night: lit windows and lamps go from a
     * warm hint at noon to glowing at midnight. 0 keeps them as they are.
     */
    val nightGlow: Float = 0f,
    /**
     * How far night is graded towards moonlight (0 off, 1 full): the sun
     * and sky dim and cool, fog and sky go deep blue, and lamps count for
     * more. The style's own lighting only takes some sun away at night,
     * which reads as an overcast afternoon; lit windows need a real dark to
     * glow against. Free: it changes the frame's lighting terms, not the shading.
     */
    val nightGrade: Float = 0f,
    /**
     * Darkening along depth creases and behind silhouettes, from the depth
     * buffer in the finishing pass (0 off). A soft ink line where forms meet
     * is most of what makes a small object read at a glance. Needs a depth
     * texture, so it is a high-tier feature.
     */
    val edges: Float = 0f,
    /**
     * Screen-space ambient occlusion in the finishing pass (0 off): a ring
     * of depth taps a few voxels wide, for the contact darkening between
     * separate pieces that per-corner occlusion cannot see (a pot on a
     * floor, a wall on the road).
     */
    val screenOcclusion: Float = 0f,
    /**
     * Tilt-shift: blur radius, as a share of the screen's height, for what
     * is far in front of or behind the focus (0 off). The shallow depth of
     * field of a macro lens is what makes a photographed model look small.
     */
    val tiltShift: Float = 0f,
    /**
     * Blocks from the camera's focus within which surfels are drawn (0 off).
     * See `SurfelScatter`: sub-microvoxel discs of grain, tufts and pebbles.
     */
    val surfelRadius: Int = 0,
    /** Most surfels one frame may draw; past it every chunk is thinned alike. */
    val surfelBudget: Int = 0,
    /** Scales how many surfels are scattered per face. */
    val surfelDensity: Float = 1f,
) {
    init {
        require(grain in 0f..0.5f) { "grain $grain is outside 0..0.5" }
        require(tiltShift in 0f..0.05f) { "tiltShift $tiltShift is outside 0..0.05" }
        require(surfelRadius >= 0 && surfelBudget >= 0) { "surfel radius and budget cannot be negative" }
    }

    /** Whether surfels are scattered and drawn at all. */
    val surfels: Boolean get() = surfelRadius > 0 && surfelBudget > 0

    /** Whether the finishing pass reads depth, and so the scene needs a depth texture rather than a renderbuffer. */
    val readsDepth: Boolean get() = edges > 0f || screenOcclusion > 0f || tiltShift > 0f

    companion object {
        /** The renderer exactly as it was: every ingredient off. */
        val OFF = DioramaLook()

        /**
         * What each tier buys. LOW keeps only the night glow and grade: one
         * multiply on a value already computed, and new lighting terms. MEDIUM adds what costs a few
         * arithmetic operations per pixel and nothing else: grain, deeper
         * occlusion and aerial haze. HIGH adds the bevels, the depth-reading
         * finish and surfels near the hero; ULTRA widens the surfel ring and
         * adds the tilt-shift.
         */
        fun of(tier: QualityTier): DioramaLook = when (tier) {
            QualityTier.LOW -> DioramaLook(nightGlow = NIGHT_GLOW, nightGrade = 1f)
            QualityTier.MEDIUM -> DioramaLook(grain = GRAIN, occlusionDepth = 0.5f, aerialHaze = true, nightGlow = NIGHT_GLOW, nightGrade = 1f)
            QualityTier.HIGH -> DioramaLook(
                grain = GRAIN, bevel = 0.55f, occlusionDepth = 0.6f, aerialHaze = true, nightGlow = NIGHT_GLOW, nightGrade = 1f,
                edges = 0.55f, screenOcclusion = 0.6f, surfelRadius = 12, surfelBudget = 60_000,
            )
            QualityTier.ULTRA -> DioramaLook(
                grain = GRAIN, bevel = 0.6f, occlusionDepth = 0.6f, aerialHaze = true, nightGlow = NIGHT_GLOW, nightGrade = 1f,
                edges = 0.6f, screenOcclusion = 0.7f, tiltShift = 0.0045f,
                surfelRadius = 18, surfelBudget = 150_000, surfelDensity = 1.3f,
            )
        }

        private const val GRAIN = 0.07f
        private const val NIGHT_GLOW = 3f
    }
}
