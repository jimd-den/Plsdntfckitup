package com.stratum.core.domain.art

import com.stratum.core.domain.ai.ImageRequest

/**
 * How much a thing is worth generating.
 *
 * The whole cost argument of this pipeline is in this enum. A world has
 * hundreds of thousands of blocks, thousands of props, dozens of monsters and
 * one player, and spending the same effort on each is the mistake that makes
 * AI-assisted art expensive and incoherent at the same time. Effort goes where
 * the eye goes.
 */
enum class AssetTier {
    /**
     * Blocks, tile variants, grass, ore, ordinary trees.
     *
     * Never generated as art. Procedural colour, a silhouette family and a
     * deterministic variant per instance. Thousands of them exist and they must
     * all agree with each other, which is a thing rules do well and a thing
     * independent generations do badly.
     */
    SYSTEMIC,

    /**
     * Shrines, ruins, landmark trees, arenas, stalls.
     *
     * One generation each, at most, and usually one per *region* rather than
     * per object: the landmark is the asset, and the region's rules place the
     * rest around it. This is where generated art earns its keep, because a
     * landmark is seen deliberately and rarely.
     */
    PROP,

    /**
     * The player, elites, bosses, weapon silhouettes.
     *
     * Generated from one curated reference so it is the same character every
     * time, posed from motion data rather than from independent prompts, and
     * accepted by a person before it ships. Expensive, few, and the only tier
     * where a human is in the loop by design.
     */
    HERO,
}

/**
 * The fixed half of every asset prompt.
 *
 * A generated world stops looking generated when the prompts stop changing. Ask
 * for a tree, then a rock, then a shrine in three freely written prompts and
 * you get three objects from three different games; hold everything but one
 * noun fixed and you get a set. That is the whole trick, and it is the reason
 * the prompt is assembled from a style record instead of typed by hand.
 */
object ArtBible {

    /**
     * The house prompt plus one subject.
     *
     * Camera, shading, edge treatment, palette and prohibitions come from the
     * style; the caller supplies a noun. Anything else the caller wants to say
     * goes in [extra], which is deliberately last and deliberately short.
     */
    fun promptFor(
        direction: ArtDirection,
        subject: String,
        tier: AssetTier = AssetTier.PROP,
        kit: BiomeArtKit? = null,
        extra: String = "",
    ): String = buildString {
        append(direction.diction.house)
        append(", ")
        append(paletteClause(direction, kit))
        direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(", $it") }
        append(", ")
        append(lightingClause(direction))
        append(", ")
        append(tierClause(tier))
        kit?.subject?.takeIf(String::isNotBlank)?.let { append(", set in $it") }
        extra.takeIf(String::isNotBlank)?.let { append(", $it") }
        append(". Single subject centred, transparent background. ")
        append(direction.diction.forbidden)
        append(". Subject: ")
        append(subject.uppercase())
        append(".")
    }

    /** The same prompt, packaged for the image port. */
    fun requestFor(
        direction: ArtDirection,
        subject: String,
        tier: AssetTier = AssetTier.PROP,
        kit: BiomeArtKit? = null,
        extra: String = "",
        width: Int = 512,
        height: Int = 512,
        modelId: String? = null,
    ): ImageRequest = ImageRequest(
        prompt = promptFor(direction, subject, tier, kit, extra),
        modelId = modelId,
        width = width,
        height = height,
        requireTransparency = true,
    )

    /**
     * The prompts for a region's whole kit, in one call.
     *
     * A region is generated as a *set* or not at all. Ordering the landmark
     * today and the props next week produces a landmark from a different world,
     * because the style will have moved on and the model certainly will have.
     */
    fun kitPrompts(direction: ArtDirection, kit: BiomeArtKit): List<AssetOrder> = buildList {
        kit.landmarkBlockId?.let { landmark ->
            add(
                AssetOrder(
                    id = "${kit.biomeId}/landmark",
                    tier = AssetTier.PROP,
                    subject = "the region's single landmark, built from ${landmark.substringAfter(':')}",
                    prompt = promptFor(
                        direction,
                        "MONUMENTAL LANDMARK OF ${landmark.substringAfter(':').uppercase().replace('_', ' ')}",
                        AssetTier.PROP,
                        kit,
                        extra = "monumental scale, visible from across the map, strong silhouette against the sky",
                    ),
                ),
            )
        }
        kit.propFamilies.forEach { (blockId, family) ->
            add(
                AssetOrder(
                    id = "${kit.biomeId}/$blockId",
                    tier = AssetTier.PROP,
                    subject = blockId,
                    prompt = promptFor(
                        direction,
                        blockId.substringAfter(':').uppercase().replace('_', ' '),
                        AssetTier.PROP,
                        kit,
                        extra = "${family.name.lowercase()} silhouette family, three size variants in one row",
                    ),
                ),
            )
        }
    }

    /**
     * The palette, in words a model follows.
     *
     * Hex codes alone are mostly ignored by image models; named roles with hex
     * codes attached are followed far more often, because the model can tell
     * which colour is meant to dominate and which is a rare accent.
     */
    /**
     * The rule every piece of scenery is painted under.
     *
     * A readable action RPG has a hierarchy: a quiet floor, calm scenery that
     * frames the space, and only then the loud things — characters, monsters,
     * loot, spells. Asked to paint a "sacred grove thick with spirit mist", a
     * model puts glowing wisps on every tree and a mosaic on every tile, and a
     * world of those is noise with nothing to look at. Glow and saturated
     * colour belong to what a player must react to, so scenery is told to
     * leave them alone.
     */
    const val SCENERY_RULE = "This is background scenery, not the focus of the game: calm and readable, " +
        "no magical effects, no glow, no mist, no smoke, no wisps, no sparkles, no floating particles, " +
        "painted in its natural local colours (green leaves, brown bark, grey stone) at mid-to-dark values, " +
        "never pale, white or washed out, slightly less saturated and darker than the characters who will stand in front of it"

    /** Floors are the quietest layer of all. */
    const val FLOOR_RULE = "A quiet background floor that characters and spell effects must stand out against: " +
        "low contrast, soft, large gentle patches of closely related colour, only a few small shapes, " +
        "no repeating pattern of stones, tiles or cells, no bright spots"

    fun paletteNote(direction: ArtDirection, kit: BiomeArtKit?, accent: Boolean = true): String {
        val palette = direction.palette
        val mood = when {
            direction.contrast.terrainValueCeiling < 0.55f -> "dark, low-key values"
            direction.contrast.terrainValueFloor > 0.35f -> "light, high-key values"
            else -> "mid-tone values"
        }
        val saturation = when {
            direction.contrast.terrainSaturation < 0.5f -> "muted, desaturated colour"
            direction.contrast.terrainSaturation > 0.9f -> "rich, saturated colour"
            else -> "moderately saturated colour"
        }
        val accentColour = kit?.sacredAccent ?: palette.sacred
        return "Palette: $mood, $saturation; light tinted ${hex(palette.sun)}, shadows tinted ${hex(palette.shadow)}" +
            if (accent) ", occasional accent ${hex(accentColour)}." else "; no accent colours."
    }

    private fun paletteClause(direction: ArtDirection, kit: BiomeArtKit?): String {
        val palette = direction.palette
        val sacred = kit?.sacredAccent ?: palette.sacred
        return "key light ${hex(palette.sun)}, shadow ${hex(palette.shadow)}, " +
            "accent ${hex(sacred)}, danger ${hex(palette.hostile)}, treasure ${hex(palette.resource)}"
    }

    private fun lightingClause(direction: ArtDirection): String {
        val light = direction.light
        val from = when {
            light.sunAzimuth < 45f || light.sunAzimuth >= 315f -> "upper left"
            light.sunAzimuth < 135f -> "upper right"
            light.sunAzimuth < 225f -> "below"
            else -> "lower left"
        }
        val hardness = if (light.sunStrength > 0.5f) "hard directional key light" else "soft diffuse key light"
        return "$hardness from the $from, cool shadow, clear separation of lit and unlit planes"
    }

    /**
     * What the tier means to the model.
     *
     * Not decoration. A prop that comes back with a hero's detail budget looks
     * wrong standing in a field of tiles, and a hero that comes back with a
     * prop's looks like scenery — the mismatch reads as a bug even when both
     * images are individually good.
     */
    private fun tierClause(tier: AssetTier): String = when (tier) {
        AssetTier.SYSTEMIC -> "simple repeatable form, minimal internal detail, reads at thumbnail size"
        AssetTier.PROP -> "distinct memorable silhouette, moderate internal detail, reads from across a screen"
        AssetTier.HERO -> "character-grade detail, strong readable silhouette first, costume and palette " +
            "consistent with a single named individual"
    }

    private fun hex(color: Long): String = hexOf(color)

    fun hexOf(color: Long): String = "#%06X".format(color and 0xFFFFFF)
}

/** One thing to generate, with the prompt already assembled. */
data class AssetOrder(
    val id: String,
    val tier: AssetTier,
    val subject: String,
    val prompt: String,
)
