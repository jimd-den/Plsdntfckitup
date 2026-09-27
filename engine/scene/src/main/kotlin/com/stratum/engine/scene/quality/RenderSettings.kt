package com.stratum.engine.scene.quality

/** How much the renderer spends per frame, from a phone that barely runs GLES 3 to a flagship. */
enum class QualityTier { LOW, MEDIUM, HIGH, ULTRA }

/**
 * Every renderer cost the game can trade away, as one value.
 *
 * Backends read these and nothing else to decide how hard to work, so a
 * tier is a row of numbers rather than branches scattered through the
 * renderer, and the CPU and GPU sides of the pipeline agree on it.
 */
data class RenderSettings(
    val tier: QualityTier,
    /** The 3D scene is drawn at this share of the screen's resolution and scaled up. */
    val renderScale: Float,
    /** How far the frame governor may lower [renderScale] when frames run long. */
    val minRenderScale: Float,
    /** Texels a side of the sun's shadow map; 0 draws no shadow map at all. */
    val shadowMapSize: Int,
    /** Shadow samples per pixel: 1 is a hard edge, 9 a soft 3x3 filter. */
    val shadowTaps: Int,
    /** Render into a half-float target so bright lights do not clip before tone mapping. */
    val highRange: Boolean,
    /** Point lights shaded per pixel, nearest first; the hero's light always counts. */
    val maxPointLights: Int,
    /** Blocks of terrain meshed around the camera. */
    val viewRadius: Int,
    /** Chunks simulated and kept in memory each way from the player. */
    val streamingRadius: Int,
    /** Memory the tile texture array may use. See `TextureBudget`. */
    val textureBudgetBytes: Long,
    /** Largest texels a side a tile texture may keep. */
    val maxTextureSize: Int,
    /** Leaves, stones and flowers scattered on open ground. */
    val groundLitter: Boolean,
    /** Drifting motes and weather particles. */
    val atmosphereMotes: Boolean,
    /** The frame rate the governor defends. */
    val targetFps: Int,
) {
    init {
        require(renderScale in minRenderScale..1f) { "renderScale $renderScale is outside $minRenderScale..1" }
        require(minRenderScale > 0f) { "minRenderScale must be positive" }
        require(shadowMapSize == 0 || Integer.bitCount(shadowMapSize) == 1) { "shadowMapSize $shadowMapSize is not a power of two" }
        require(shadowTaps == 1 || shadowTaps == 9) { "shadowTaps is 1 or 9, not $shadowTaps" }
        require(maxPointLights in 0..MAX_POINT_LIGHTS) { "maxPointLights must be 0..$MAX_POINT_LIGHTS" }
        require(targetFps > 0) { "targetFps must be positive" }
    }

    val shadows: Boolean get() = shadowMapSize > 0

    /** Milliseconds one frame may take at [targetFps]. */
    val frameBudgetMillis: Float get() = 1000f / targetFps

    /**
     * These settings with anything the device cannot do taken out: a shadow
     * map larger than its largest texture, a high-range target it cannot
     * render to, tiles larger than it can hold.
     */
    fun fittedTo(device: DeviceProfile): RenderSettings = copy(
        shadowMapSize = if (shadowMapSize > device.maxTextureSize) Integer.highestOneBit(device.maxTextureSize) else shadowMapSize,
        highRange = highRange && device.canRenderHalfFloat,
        maxTextureSize = minOf(maxTextureSize, device.maxTextureSize),
    )

    companion object {
        /** What the shaders are compiled for. */
        const val MAX_POINT_LIGHTS = 8

        fun of(tier: QualityTier): RenderSettings = when (tier) {
            // A phone with 2 GB and a GPU from 2016: fewer pixels, no shadow
            // map, a couple of lights, a short view. Still the same world.
            QualityTier.LOW -> RenderSettings(
                tier, renderScale = 0.6f, minRenderScale = 0.45f, shadowMapSize = 0, shadowTaps = 1, highRange = false,
                maxPointLights = 2, viewRadius = 32, streamingRadius = 2, textureBudgetBytes = 24L * MB, maxTextureSize = 128,
                groundLitter = false, atmosphereMotes = false, targetFps = 30,
            )
            QualityTier.MEDIUM -> RenderSettings(
                tier, renderScale = 0.8f, minRenderScale = 0.55f, shadowMapSize = 1024, shadowTaps = 1, highRange = true,
                maxPointLights = 4, viewRadius = 44, streamingRadius = 3, textureBudgetBytes = 48L * MB, maxTextureSize = 256,
                groundLitter = true, atmosphereMotes = false, targetFps = 30,
            )
            // What the game drew before tiers existed.
            QualityTier.HIGH -> RenderSettings(
                tier, renderScale = 1f, minRenderScale = 0.7f, shadowMapSize = 2048, shadowTaps = 9, highRange = true,
                maxPointLights = MAX_POINT_LIGHTS, viewRadius = 56, streamingRadius = 4, textureBudgetBytes = 96L * MB, maxTextureSize = 512,
                groundLitter = true, atmosphereMotes = true, targetFps = 60,
            )
            QualityTier.ULTRA -> RenderSettings(
                tier, renderScale = 1f, minRenderScale = 0.85f, shadowMapSize = 4096, shadowTaps = 9, highRange = true,
                maxPointLights = MAX_POINT_LIGHTS, viewRadius = 72, streamingRadius = 5, textureBudgetBytes = 160L * MB, maxTextureSize = 512,
                groundLitter = true, atmosphereMotes = true, targetFps = 60,
            )
        }

        private const val MB = 1024L * 1024
    }
}
