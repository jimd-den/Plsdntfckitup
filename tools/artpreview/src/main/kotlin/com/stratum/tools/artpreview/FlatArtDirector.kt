package com.stratum.tools.artpreview

import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.ActorStyle
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.AtmosphereStyle
import com.stratum.core.domain.art.CombatCue
import com.stratum.core.domain.art.MoteKind
import com.stratum.core.domain.art.PropCue
import com.stratum.core.domain.art.PropStyle
import com.stratum.core.domain.art.PropSilhouette
import com.stratum.core.domain.art.TerrainCue
import com.stratum.core.domain.art.TerrainStyle
import com.stratum.core.domain.art.Tint
import com.stratum.core.domain.art.VisualEffect
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.BiomeDefinition

/**
 * The look the game had before there was an art layer, kept as a director.
 *
 * Every rule this class implements was, until recently, a constant inside the
 * Compose canvas: two fixed face shades, one depth ramp, one ledge alpha, one
 * seam, no lighting model, no atmosphere and no contrast budget. Preserving it
 * as a style rather than deleting it is worth the fifty lines — it is the
 * control the new work is measured against, and a comparison against a
 * remembered screenshot is not a comparison.
 */
class FlatArtDirector : WorldArtDirector {

    override val direction: ArtDirection = ArtDirection(
        id = "stratum:flat",
        name = "Before",
        summary = "Flat pack colours, fixed face shades, no lighting model and no air.",
    )

    override fun terrainStyleFor(cue: TerrainCue): TerrainStyle {
        val depth = (cue.depthBelowEye.coerceIn(0, DEPTH_RANGE)).toFloat() / DEPTH_RANGE
        val shade = 1f - depth * DEPTH_STRENGTH
        val grain = if (cue.isTop) 1f + (cue.grain - 0.5f) * GRAIN else 1f
        return TerrainStyle(
            top = Tint.scale(cue.block.topColor, shade * grain),
            left = Tint.scale(cue.block.sideColor, shade * LEFT_FACE),
            right = Tint.scale(cue.block.sideColor, shade * RIGHT_FACE),
            occlusion = if (cue.ledgeShadow) Tint.withAlpha(0xFF000000, LEDGE_ALPHA) else 0x00000000,
            seam = Tint.withAlpha(0xFF000000, SEAM_ALPHA),
        )
    }

    /** Props were emoji, and an emoji has no silhouette family. Closest is a blob. */
    override fun propStyleFor(cue: PropCue): PropStyle? {
        if (cue.block.glyph == null) return null
        return PropStyle(
            silhouette = PropSilhouette.CANOPY,
            variant = 0,
            fill = cue.block.topColor,
            shade = cue.block.topColor,
            support = cue.block.sideColor,
            outline = 0x00000000,
            outlineWidth = 0f,
            scale = PROP_SCALE * cue.block.glyphScale,
            contactShadow = 0f,
            glyph = cue.block.glyph,
        )
    }

    override fun actorStyleFor(actor: ActorPresentation): ActorStyle = ActorStyle(
        scale = 1f,
        body = when (actor.role) {
            ActorRole.PLAYER -> 0xFFF4EBDC
            ActorRole.ENEMY -> 0xFFC1453B
            else -> 0xFFD9A441
        },
        outline = 0xFF14110E,
        outlineWidth = 4f,
        rim = 0xFFCD7F32,
        rimStrength = 0f,
        contactShadow = 0.55f,
    )

    override fun atmosphereFor(biome: BiomeDefinition?, time: WorldTime): AtmosphereStyle = AtmosphereStyle(
        skyTop = 0xFF12131A,
        skyBottom = 0xFF12131A,
        haze = 0x00000000,
        hazeStrength = 0f,
        vignette = 0f,
        overlay = 0x00000000,
        moteKind = MoteKind.NONE,
        moteDensity = 0,
        moteColor = 0x00000000,
        moteDrift = 0f,
    )

    override fun effectsFor(cue: CombatCue): List<VisualEffect> = emptyList()

    private companion object {
        const val DEPTH_RANGE = 8
        const val DEPTH_STRENGTH = 0.45f
        const val GRAIN = 0.07f
        const val LEFT_FACE = 0.72f
        const val RIGHT_FACE = 0.52f
        const val LEDGE_ALPHA = 0.22f
        const val SEAM_ALPHA = 0.10f
        const val PROP_SCALE = 0.62f
    }
}
