package com.stratum.core.domain.art

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType
import kotlin.math.abs

/**
 * The one director everything ships with: an [ArtDirection] applied uniformly.
 *
 * It is a pure function of the style and the cue, with no state and no
 * allocation beyond what it returns, because it is called once per visible cube
 * per frame — on a phone, tens of thousands of times a second. That budget is
 * why the shading is arithmetic on packed longs rather than anything cleverer.
 *
 * Other directors are expected. A cutscene director that dims everything but
 * one actor, a boss-arena director that recolours a region while a fight is
 * running, a debug director that renders materials flat — each is this
 * interface with a different opinion, and the renderer cannot tell them apart.
 */
class StyleSheetArtDirector(
    override val direction: ArtDirection = ArtDirection.HOUSE,
    /** Per-region ingredients, usually from [BiomeArtKit.deriveAll]. */
    private val kits: Map<String, BiomeArtKit> = emptyMap(),
) : WorldArtDirector, SceneArtDirector {

    private val palette = direction.palette
    private val light = direction.light
    private val contrast = direction.contrast
    private val shape = direction.shape

    /**
     * Face exposures are three numbers for the whole frame, so they are worked
     * out once. A cube's faces always point the same way — that is what makes
     * it an isometric projection — and recomputing the sun angle per block was
     * a cosine per face per cube per frame for an answer that never changed.
     */
    private val topExposure = light.exposure(TOP_FACING)
    private val leftExposure = light.exposure(LEFT_FACING)
    private val rightExposure = light.exposure(RIGHT_FACING)

    override fun terrainStyleFor(cue: TerrainCue): TerrainStyle {
        val kit = cue.biomeId?.let(kits::get)
        val ramp = rampFor(kit)

        // Depth first, because it is the strongest cue in the image and the one
        // the player actually navigates by: "am I a level down from that" has
        // to survive every other adjustment made after it.
        val depth = (cue.depthBelowEye.coerceIn(0, light.depthRange)).toFloat() / light.depthRange
        val depthShade = 1f - depth * light.depthFalloff

        // Grain only on the surface. Applying it to side faces made cliffs
        // sparkle, which reads as noise rather than as material.
        val grain = if (cue.isTop) 1f + (cue.grain - 0.5f) * shape.grain else 1f

        val blockLight = (cue.lightLevel.coerceIn(0, MAX_BLOCK_LIGHT)).toFloat() / MAX_BLOCK_LIGHT
        val lightFloor = LIGHT_FLOOR + (1f - LIGHT_FLOOR) * blockLight

        val top = faceColor(cue.block.topColor, topExposure, depthShade * grain * lightFloor, cue, ramp, kit)
        val left = faceColor(cue.block.sideColor, leftExposure, depthShade * lightFloor, cue, ramp, kit)
        val right = faceColor(cue.block.sideColor, rightExposure, depthShade * lightFloor, cue, ramp, kit)

        val occlusion = when {
            cue.cornerShadow -> Tint.withAlpha(palette.ink, light.ledgeOcclusion + light.cornerOcclusion)
            cue.ledgeShadow -> Tint.withAlpha(palette.ink, light.ledgeOcclusion)
            else -> 0x00000000L
        }

        // A contour only on the walkable surface of a terrace. Outlining every
        // face turned a cliff into a wireframe; outlining only the lid is what
        // makes a ledge read as a step you could climb.
        val wantsEdge = cue.isTop && shape.terraceEmphasis > 0f
        return TerrainStyle(
            top = top,
            left = left,
            right = right,
            occlusion = occlusion,
            seam = Tint.withAlpha(palette.ink, shape.seam),
            edge = if (wantsEdge) Tint.withAlpha(palette.ink, EDGE_ALPHA * shape.terraceEmphasis) else 0x00000000L,
            edgeWidth = if (wantsEdge) shape.terraceEmphasis else 0f,
        )
    }

    /**
     * One face, all the way through: exposure, shadow tint, the contrast
     * contract, emissive bleed, distance haze and finally the ramp.
     *
     * The order matters and is not arbitrary. Clamping before the sun would
     * flatten the lighting; hazing before the clamp would let distant ground
     * escape the band and start competing with actors; quantising anywhere but
     * last would put colours in the frame that are not in the ramp.
     */
    /**
     * One surface, lit: the contract, then the sun, then the shadow.
     *
     * Shared by terrain and props so the two cannot drift apart. [headroom]
     * lifts the ceiling of the value band, which is how a prop is allowed to be
     * brighter than the ground it stands on without escaping the contract.
     */
    private fun shadePlane(
        base: Long,
        saturation: Float,
        exposure: Float,
        shade: Float,
        headroom: Float = 0f,
    ): Long {
        val band = contrast.terrainValueBand
        var color = Tint.saturate(base, saturation)
        color = Tint.clampLuma(color, band.start, (band.endInclusive + headroom).coerceAtMost(MAX_PLANE_VALUE))
        val lit = (exposure * shade).coerceIn(0f, 2f)
        val sunward = (exposure - light.ambientStrength).coerceAtLeast(0f)
        color = Tint.scale(Tint.mix(color, palette.sun, SUN_BLEED * sunward), lit)
        val shadowward = (1f - exposure / (light.ambientStrength + light.sunStrength)).coerceIn(0f, 1f)
        return Tint.mix(color, palette.shadow, light.shadowTint * shadowward)
    }

    /** The style's own ramp first, the region's only if the style asked for it. */
    private fun rampFor(kit: BiomeArtKit?): List<Long> = when {
        palette.ramp.isNotEmpty() -> palette.ramp
        shape.quantizeToRegion -> kit?.groundRamp.orEmpty()
        else -> emptyList()
    }

    private fun faceColor(
        base: Long,
        exposure: Float,
        shade: Float,
        cue: TerrainCue,
        ramp: List<Long>,
        kit: BiomeArtKit?,
    ): Long {
        // Warm light, cool shade: the lit share of the face takes the sun's
        // colour, the rest is pulled towards the shadow. One multiply and one
        // mix, and it is most of what separates an illustration from a chart.
        var color = shadePlane(base, contrast.terrainSaturation, exposure, shade)
        kit?.shadowTint?.let { regional ->
            val shadowward = (1f - exposure / (light.ambientStrength + light.sunStrength)).coerceIn(0f, 1f)
            color = Tint.mix(color, regional, light.shadowTint * shadowward)
        }

        if (cue.emissive > 0f && Tint.alpha(cue.emissiveColor) > 0) {
            // Eased, because a linear falloff over whole cells draws visible
            // diamond bands around every torch — which reads as a rendering
            // fault rather than as light.
            val eased = cue.emissive * cue.emissive * (3f - 2f * cue.emissive)
            color = Tint.mix(color, cue.emissiveColor, eased * light.emissiveStrength * exposure / 2f)
        }

        // A block that emits its own light is not subject to the terrain
        // contract: an ore seam or a brazier is exactly the thing that is meant
        // to pull the eye across a field of ground.
        if (cue.block.lightEmission > 0) {
            color = Tint.saturate(color, contrast.featureSaturation)
        }

        if (cue.distance > 0f) {
            color = Tint.veil(color, direction.atmosphere.hazeColor, cue.distance * direction.atmosphere.hazeStrength)
        }

        return Tint.quantize(color, ramp)
    }

    override fun surfaceFor(block: BlockType, face: SurfaceFace, biomeId: String?): SurfaceStyle {
        val kit = biomeId?.let(kits::get)
        val base = if (face == SurfaceFace.TOP) block.topColor else block.sideColor
        val feature = block.lightEmission > 0 || block.material == BlockMaterial.ORE
        val band = contrast.terrainValueBand
        var albedo = Tint.saturate(base, if (feature) contrast.featureSaturation else contrast.terrainSaturation)
        if (!feature) albedo = Tint.clampLuma(albedo, band.start, band.endInclusive)
        albedo = Tint.quantize(albedo, rampFor(kit))
        val emission = block.lightEmission.toFloat() / MAX_BLOCK_LIGHT
        return SurfaceStyle(
            albedo = albedo,
            texture = "${block.id}/${face.name.lowercase()}",
            emissive = emission * light.emissiveStrength,
            emissiveColor = if (Tint.alpha(block.accentColor) > 0) block.accentColor else palette.resource,
        )
    }

    override fun lightingFor(biomeId: String?, time: WorldTime): SceneLighting {
        val atmosphere = atmosphereFor(null, time)
        val night = nightFraction(time)
        // The 2D azimuth is measured on screen; the scene needs it on the
        // ground. Screen-up and screen-right are the two diagonals of the grid
        // the isometric camera looks along, so the conversion is one rotation.
        val azimuth = Math.toRadians(light.sunAzimuth.toDouble())
        val elevation = Math.toRadians(light.sunElevation.toDouble())
        val upX = -INV_SQRT2
        val upY = -INV_SQRT2
        val rightX = INV_SQRT2
        val rightY = -INV_SQRT2
        val horizontalX = (kotlin.math.cos(azimuth) * upX + kotlin.math.sin(azimuth) * rightX).toFloat()
        val horizontalY = (kotlin.math.cos(azimuth) * upY + kotlin.math.sin(azimuth) * rightY).toFloat()
        val flat = kotlin.math.cos(elevation).toFloat()
        return SceneLighting(
            sunX = horizontalX * flat,
            sunY = horizontalY * flat,
            sunZ = kotlin.math.sin(elevation).toFloat(),
            sunColor = palette.sun,
            sunIntensity = light.sunStrength * SUN_GAIN * (1f - night * NIGHT_SUN_LOSS),
            skyAmbient = Tint.mix(palette.ambient, palette.sun, SKY_WARMTH),
            groundAmbient = palette.shadow,
            ambientIntensity = light.ambientStrength * AMBIENT_GAIN,
            shadowStrength = (SHADOW_BASE + light.shadowTint * SHADOW_TINT_GAIN).coerceIn(0f, 0.95f),
            fogColor = atmosphere.haze,
            fogStart = FOG_NEAR,
            fogEnd = FOG_NEAR + FOG_SPAN * (1f - atmosphere.hazeStrength),
            fogFloor = 0f,
            rimColor = palette.heroRim,
            rimStrength = contrast.rimStrength,
            exposure = 1f,
            saturation = SATURATION_BASE + SATURATION_GAIN * contrast.terrainSaturation,
            vignette = atmosphere.vignette,
            skyTop = atmosphere.skyTop,
            skyBottom = atmosphere.skyBottom,
            pointLightGain = light.emissiveStrength * POINT_GAIN,
            heroLight = light.heroLight * (1f + night),
            heroLightRadius = HERO_LIGHT_RADIUS,
        )
    }

    override fun propStyleFor(cue: PropCue): PropStyle? {
        val block = cue.block
        // A prop is a block a pack marked as one. Terrain stays cubes, because
        // the depth and material grammar of the terrain is the point of it.
        if (block.glyph == null && block.material != BlockMaterial.FOLIAGE) return null

        val kit = cue.biomeId?.let(kits::get)
        val family = kit?.propFamilies?.get(block.id) ?: PropSilhouette.forMaterial(block)

        val depth = (cue.depthBelowEye.coerceIn(0, light.depthRange)).toFloat() / light.depthRange
        val shade = 1f - depth * light.depthFalloff

        // Props keep more of their colour than the ground does. They are the
        // middle tier of the contract: louder than terrain so a grove reads as
        // a grove, quieter than an actor so a tree is never mistaken for a
        // threat.
        //
        // Lit through the same chain as the terrain, and that matters more than
        // it sounds: props cover most of a forested frame, and when they were
        // shaded by a bare multiply they ignored the palette entirely — every
        // style came back with the same dark green canopy over it, and the
        // styles stopped being distinguishable at a glance.
        val ramp = rampFor(kit)
        var fill = shadePlane(
            base = block.topColor,
            saturation = (contrast.terrainSaturation + 1f) / 2f,
            exposure = topExposure,
            shade = shade,
            headroom = PROP_HEADROOM,
        )
        if (cue.distance > 0f) {
            fill = Tint.veil(fill, direction.atmosphere.hazeColor, cue.distance * direction.atmosphere.hazeStrength)
        }
        fill = Tint.quantize(fill, ramp)

        val variance = 1f + (variantNoise(cue.variant) - 0.5f) * shape.propVariance * 2f

        return PropStyle(
            silhouette = family,
            variant = cue.variant,
            fill = fill,
            shade = Tint.quantize(
                Tint.mix(Tint.scale(fill, rightExposure / topExposure), palette.shadow, light.shadowTint),
                ramp,
            ),
            support = Tint.quantize(
                shadePlane(
                    base = block.sideColor,
                    saturation = contrast.terrainSaturation,
                    exposure = leftExposure,
                    shade = shade,
                    headroom = PROP_HEADROOM,
                ),
                ramp,
            ),
            outline = Tint.mix(fill, palette.ink, contrast.outlineDarkness),
            outlineWidth = shape.propOutline,
            scale = shape.propScale * block.glyphScale * variance,
            contactShadow = contrast.contactShadow * PROP_SHADOW_SHARE,
            glow = if (block.lightEmission > 0) {
                Tint.withAlpha(
                    if (Tint.alpha(block.accentColor) > 0) block.accentColor else palette.resource,
                    (block.lightEmission.toFloat() / MAX_BLOCK_LIGHT) * light.emissiveStrength,
                )
            } else {
                0x00000000L
            },
            glyph = block.glyph,
        )
    }

    override fun actorStyleFor(actor: ActorPresentation): ActorStyle {
        val accent = actor.accent?.takeIf { Tint.alpha(it) > 0 }
        val depth = (actor.depthBelowEye.coerceIn(0, light.depthRange)).toFloat() / light.depthRange
        // Actors shade with their ground, but at half strength. Fully shaded,
        // a player who stepped into a pit became unreadable — which is exactly
        // the moment a player most needs to be readable.
        val shade = 1f - depth * light.depthFalloff * ACTOR_DEPTH_SHARE

        val body = when (actor.role) {
            ActorRole.PLAYER -> accent ?: palette.heroRim
            ActorRole.ENEMY -> accent ?: palette.hostile
            ActorRole.ALLY -> accent ?: palette.sacred
            ActorRole.LOOT -> accent ?: palette.resource
            ActorRole.INTERACTABLE -> accent ?: palette.resource
            ActorRole.LANDMARK -> accent ?: palette.sacred
        }

        val scale = when (actor.role) {
            ActorRole.PLAYER -> contrast.heroScale
            ActorRole.ENEMY -> contrast.enemyScale * (actor.rank?.presenceScale() ?: 1f)
            ActorRole.LANDMARK -> contrast.heroScale * LANDMARK_SCALE
            else -> 1f
        }

        // Elites get a ring of their own rather than a bigger health bar. A
        // player reacting at combat speed reads the floor, not the furniture.
        val ring = when (actor.rank) {
            EnemyRank.ELITE -> Tint.withAlpha(palette.hostile, ELITE_RING_ALPHA)
            EnemyRank.CHAMPION -> Tint.withAlpha(palette.sacred, ELITE_RING_ALPHA)
            EnemyRank.BOSS -> Tint.withAlpha(palette.hostile, BOSS_RING_ALPHA)
            else -> 0x00000000L
        }

        return ActorStyle(
            scale = scale,
            body = Tint.scale(Tint.saturate(body, contrast.featureSaturation), shade),
            outline = Tint.mix(body, palette.ink, contrast.outlineDarkness),
            outlineWidth = contrast.outlineWidth,
            rim = when (actor.role) {
                ActorRole.PLAYER -> palette.heroRim
                ActorRole.ENEMY -> Tint.mix(palette.hostile, palette.sun, RIM_WARMTH)
                else -> palette.sun
            },
            rimStrength = contrast.rimStrength,
            contactShadow = contrast.contactShadow * (1f + actor.impact * contrast.contactSpread),
            groundRing = ring,
            halo = when (actor.role) {
                ActorRole.LOOT, ActorRole.INTERACTABLE ->
                    Tint.withAlpha(body, contrast.interactableHalo)
                else -> 0x00000000L
            },
            flashTint = if (actor.flash > 0f) Tint.withAlpha(palette.sun, actor.flash) else 0x00000000L,
        )
    }

    override fun atmosphereFor(biome: BiomeDefinition?, time: WorldTime): AtmosphereStyle {
        val base = direction.atmosphere
        val kit = biome?.id?.let(kits::get)
        val weather = kit?.atmosphere

        // Night deepens the haze and drags the sky down rather than swapping
        // palettes. A world that changes colour scheme at dusk stops being the
        // same world; one that gets darker and hazier is the same world at night.
        val night = nightFraction(time)
        val underground = if (time.isUnderground) 1f else 0f
        val gloom = maxOf(night, underground)

        return AtmosphereStyle(
            skyTop = Tint.mix(base.skyTop, base.hazeColor, gloom * NIGHT_SKY_PULL),
            skyBottom = Tint.mix(base.skyBottom, base.hazeColor, gloom * NIGHT_SKY_PULL),
            haze = base.hazeColor,
            hazeStrength = (base.hazeStrength * (1f + gloom * NIGHT_HAZE_GAIN)).coerceIn(0f, 1f),
            vignette = (base.vignette + gloom * NIGHT_VIGNETTE_GAIN).coerceIn(0f, 1f),
            overlay = base.overlay,
            moteKind = weather?.moteKind ?: base.moteKind,
            moteDensity = weather?.moteDensity ?: base.moteDensity,
            moteColor = weather?.moteColor ?: base.moteColor,
            moteDrift = weather?.moteDrift ?: base.moteDrift,
        )
    }

    /**
     * How dark it is, from the time of day.
     *
     * A triangle rather than a sine: the useful property is that noon is zero
     * and midnight is one with a smooth walk between, and the difference
     * between that and a real solar curve is not visible through fog.
     */
    private fun nightFraction(time: WorldTime): Float {
        val phase = ((time.dayFraction % 1f) + 1f) % 1f
        return (abs(phase - NOON) / NOON).coerceIn(0f, 1f)
    }

    /**
     * The combat theatre for one moment.
     *
     * Deliberately generous. This is the cheapest quality in the whole game: a
     * ring, a flash and a shake cost a few dozen draw calls between them and do
     * more for how a hit feels than another eight frames of animation on the
     * body would.
     */
    override fun effectsFor(cue: CombatCue): List<VisualEffect> {
        val strength = cue.emphasis.coerceIn(0f, 1f)
        return when (cue.kind) {
            // The swing is drawn whether or not it lands: a whiff that leaves
            // no trace feels like the button did nothing.
            CombatMoment.SWING -> listOf(
                VisualEffect(EffectKind.WEAPON_ARC, cue.color, radius = 1.3f, duration = 0.24f, intensity = 0.6f + strength * 0.4f),
            )
            CombatMoment.HIT -> listOf(
                VisualEffect(EffectKind.HIT_FLASH, palette.sun, duration = 0.12f, intensity = 0.6f + strength * 0.4f),
                VisualEffect(EffectKind.IMPACT_RING, cue.color, radius = 0.7f + strength, duration = 0.22f),
                VisualEffect(EffectKind.DEBRIS, cue.color, radius = 0.8f, duration = 0.35f, intensity = 0.3f + strength * 0.3f),
                VisualEffect(EffectKind.NUMBER, cue.color, intensity = strength),
            )
            CombatMoment.CRITICAL -> listOf(
                VisualEffect(EffectKind.HIT_FLASH, palette.sun, duration = 0.18f, intensity = 1f),
                VisualEffect(EffectKind.IMPACT_RING, palette.resource, radius = 1.6f + strength, duration = 0.3f),
                VisualEffect(EffectKind.DEBRIS, cue.color, radius = 1.2f, duration = 0.5f, intensity = strength),
                VisualEffect(EffectKind.SCREEN_SHAKE, palette.ink, duration = 0.14f, intensity = 0.5f + strength * 0.5f),
                VisualEffect(EffectKind.NUMBER, palette.resource, intensity = 1f),
            )
            CombatMoment.BLOCKED -> listOf(
                VisualEffect(EffectKind.IMPACT_RING, palette.heroRim, radius = 0.6f, duration = 0.18f),
                VisualEffect(EffectKind.NUMBER, palette.heroRim, intensity = 0.3f),
            )
            CombatMoment.DODGED -> listOf(
                VisualEffect(EffectKind.AFTERIMAGE, palette.heroRim, duration = 0.25f, intensity = 0.7f),
                VisualEffect(EffectKind.NUMBER, palette.heroRim, intensity = 0.2f),
            )
            CombatMoment.KILL -> listOf(
                VisualEffect(EffectKind.DEBRIS, cue.color, radius = 1.5f, duration = 0.6f, intensity = 1f),
                VisualEffect(EffectKind.IMPACT_RING, palette.hostile, radius = 1.8f, duration = 0.35f),
                VisualEffect(EffectKind.SCREEN_SHAKE, palette.ink, duration = 0.1f, intensity = 0.35f),
            )
            CombatMoment.HEAL -> listOf(
                VisualEffect(EffectKind.GROUND_DECAL, palette.sacred, radius = 1.1f, duration = 0.8f),
                VisualEffect(EffectKind.NUMBER, palette.sacred, intensity = strength),
            )
            CombatMoment.CAST -> listOf(
                VisualEffect(EffectKind.GROUND_DECAL, cue.color, radius = 1.4f, duration = 0.45f),
                VisualEffect(EffectKind.WEAPON_ARC, cue.color, radius = 1.2f, duration = 0.3f, intensity = strength),
            )
            CombatMoment.DASH -> listOf(
                VisualEffect(EffectKind.AFTERIMAGE, palette.heroRim, duration = 0.3f, intensity = 1f),
            )
            CombatMoment.LOOT_DROP -> listOf(
                VisualEffect(EffectKind.BEAM, cue.color, radius = 0.4f, duration = 1.2f, intensity = 0.8f),
                VisualEffect(EffectKind.GROUND_DECAL, cue.color, radius = 0.6f, duration = 1.2f),
            )
            CombatMoment.LEVEL_UP -> listOf(
                VisualEffect(EffectKind.BEAM, palette.resource, radius = 1f, duration = 1.4f, intensity = 1f),
                VisualEffect(EffectKind.IMPACT_RING, palette.resource, radius = 2.4f, duration = 0.6f),
                VisualEffect(EffectKind.NUMBER, palette.resource, intensity = 1f),
            )
        }
    }

    /** Deterministic 0..1 from a variant index, so one tree is not every tree. */
    private fun variantNoise(variant: Int): Float {
        var hash = (variant * 374761393L + direction.seed * 668265263L) and 0x7FFFFFFF
        hash = (hash xor (hash shr 13)) * 1274126177L and 0x7FFFFFFF
        return (hash and 0xFFFF).toFloat() / 0xFFFF
    }

    private fun EnemyRank.presenceScale(): Float = when (this) {
        EnemyRank.MINION -> 1f
        EnemyRank.ELITE -> 1.2f
        EnemyRank.CHAMPION -> 1.45f
        EnemyRank.BOSS -> 1.9f
    }

    private companion object {
        /** Screen-space facings of a cube's three drawn faces, in the projection's degrees. */
        const val TOP_FACING = 0f
        const val LEFT_FACING = 240f
        const val RIGHT_FACING = 120f

        const val MAX_BLOCK_LIGHT = 15

        // The 3D lighting model's gains. Chosen so the house style's numbers,
        // which were tuned for flat 2D face shades, land at a similar overall
        // brightness once they are a real sun and a real sky.
        const val INV_SQRT2 = 0.70710678
        const val SUN_GAIN = 2.7f
        const val AMBIENT_GAIN = 1.35f
        const val NIGHT_SUN_LOSS = 0.6f
        const val SKY_WARMTH = 0.25f
        const val SHADOW_BASE = 0.45f
        const val SHADOW_TINT_GAIN = 0.5f
        const val FOG_NEAR = 6f
        const val FOG_SPAN = 30f
        const val HERO_LIGHT_RADIUS = 7.5f
        const val SATURATION_BASE = 0.55f
        const val SATURATION_GAIN = 0.5f
        const val POINT_GAIN = 1.6f
        /**
         * How dark an unlit cell gets.
         *
         * Not low. Block light is a bonus on top of the lighting model, not a
         * gate in front of it: multiplying everything by it made an open field
         * at noon come back nearly black, because nothing in the simulation
         * writes light yet and every cell reported zero.
         */
        const val LIGHT_FLOOR = 0.72f
        /** How much of the sun's own colour a fully lit face takes. */
        const val SUN_BLEED = 0.55f
        const val EDGE_ALPHA = 0.18f
        const val PROP_SHADOW_SHARE = 0.7f
        /** How far above the ground's value ceiling a prop may sit. */
        const val PROP_HEADROOM = 0.12f
        const val MAX_PLANE_VALUE = 0.95f
        const val ACTOR_DEPTH_SHARE = 0.5f
        const val ELITE_RING_ALPHA = 0.55f
        const val BOSS_RING_ALPHA = 0.75f
        const val RIM_WARMTH = 0.35f
        const val LANDMARK_SCALE = 2.5f
        const val NOON = 0.5f
        const val NIGHT_SKY_PULL = 0.65f
        const val NIGHT_HAZE_GAIN = 0.8f
        const val NIGHT_VIGNETTE_GAIN = 0.25f
    }
}
