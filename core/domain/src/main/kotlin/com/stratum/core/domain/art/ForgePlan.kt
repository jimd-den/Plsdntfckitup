package com.stratum.core.domain.art

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType

/** What an asset is for, which decides both how it is asked for and how it is cleaned up. */
enum class AssetKind {
    /** A seamless texture laid across the tops of blocks. Seen from above. */
    GROUND_TILE,

    /** A seamless texture for vertical faces: cliffs, block sides, walls. */
    WALL_TILE,

    /** One object on a keyable background, cut out and stood up in the world. */
    PROP_SPRITE,
}

/**
 * One asset to generate, fully specified.
 *
 * [key] is the texture key the renderer will look it up by — the same string
 * the art director hands out — which is the whole of the join between "what
 * the forge made" and "where the world draws it". No registry, no manifest.
 */
data class ForgeOrder(
    val key: String,
    val kind: AssetKind,
    val tier: AssetTier,
    val subject: String,
    val prompt: String,
)

/**
 * Decides what a world needs generating.
 *
 * Reads a content pack — any pack, including one a model wrote a minute ago —
 * and produces a small, bounded list of orders: the ground each region walks
 * on, the faces its cliffs show, the walls a player can build, and one sprite
 * per kind of prop. Never per block, never per acre. A pack with five regions
 * comes to a couple of dozen images, which at current prices is cents, and
 * then the whole world is drawn from them.
 */
object ForgePlanner {

    /** Colour a sprite is drawn on so it can be cut out; nothing in a world is this colour. */
    const val KEY_COLOR = 0xFFFF00FF
    private const val KEY_NAME = "pure flat magenta #FF00FF"

    fun plan(
        direction: ArtDirection,
        pack: ContentPack,
        /** Regions to cover; empty means all of them. */
        biomeIds: Set<String> = emptySet(),
    ): List<ForgeOrder> {
        val blocks = pack.blocks.associateBy { it.id }
        val orders = LinkedHashMap<String, ForgeOrder>()
        fun add(order: ForgeOrder) { orders.putIfAbsent(order.key, order) }

        val kits = BiomeArtKit.deriveAll(pack)
        pack.biomes.filter { biomeIds.isEmpty() || it.id in biomeIds }.forEach { biome ->
            val kit = kits[biome.id]
            val setting = biome.description.ifBlank { biome.name }
            blocks[biome.surfaceBlockId]?.let { surface ->
                add(tile(direction, surface, AssetKind.GROUND_TILE, setting, kit))
                add(tile(direction, surface, AssetKind.WALL_TILE, setting, kit))
            }
            blocks[biome.subsurfaceBlockId]?.let {
                add(tile(direction, it, AssetKind.WALL_TILE, setting, kit))
                // Dug out, built on, trodden into paths: the layer under the
                // surface is the ground a player's own work stands on.
                add(tile(direction, it, AssetKind.GROUND_TILE, setting, kit))
            }
            blocks[biome.bedrockFillerBlockId]?.let { add(tile(direction, it, AssetKind.WALL_TILE, setting, kit)) }
            biome.scatter.forEach { rule ->
                blocks[rule.blockId]?.let { add(sprite(direction, it, setting, kit)) }
                rule.capBlockId?.let { cap -> blocks[cap]?.let { add(sprite(direction, it, setting, kit)) } }
            }
        }
        // What the player builds with is part of the world's look whichever
        // region they build it in.
        pack.blocks.filter { it.shape == BlockShape.WALL }.forEach { wall ->
            add(tile(direction, wall, AssetKind.WALL_TILE, "a built compound wall", null))
            add(tile(direction, wall, AssetKind.GROUND_TILE, "the top of a built compound wall", null))
        }
        // Light sources are the props a player looks at most.
        // Liquids are surfaces, not objects: asked for as a sprite, "spirit
        // water" came back as a potion bottle.
        pack.blocks
            .filter { it.glyph != null && it.lightEmission > 0 && it.material != BlockMaterial.LIQUID }
            .forEach { add(sprite(direction, it, "", null)) }
        return orders.values.toList()
    }

    private fun tile(direction: ArtDirection, block: BlockType, kind: AssetKind, setting: String, kit: BiomeArtKit?): ForgeOrder {
        val face = if (kind == AssetKind.GROUND_TILE) "top" else "side"
        val material = block.displayName.lowercase()
        val view = if (kind == AssetKind.GROUND_TILE) {
            "straight top-down orthographic view of the ground surface: $material"
        } else {
            "straight-on orthographic front view of a vertical cliff or wall face made of $material"
        }
        val base = ArtBible.hexOf(if (kind == AssetKind.GROUND_TILE) block.topColor else block.sideColor)
        val prompt = buildString {
            append("SEAMLESS TILEABLE GAME TEXTURE. ")
            append(view)
            // The block's own colour, stated as the dominant one. Without it the
            // region's description wins: red earth in a mossy grove came back
            // teal, which is the grove's colour and not the earth's.
            append(", dominant base colour $base")
            if (setting.isNotBlank()) append(", found in $setting")
            append(". The texture fills the entire square frame edge to edge and wraps seamlessly on all four sides. ")
            append("Hand-painted stylized action RPG surface, broad readable brush strokes, subtle large-scale variation, ")
            append("even flat lighting with no directional shadows, no objects, no horizon, no perspective, no border, no vignette. ")
            append(ArtBible.paletteNote(direction, kit))
            direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(" Style: $it.") }
            append(" ")
            append(direction.diction.forbidden)
            append(".")
        }
        return ForgeOrder("${block.id}/$face", kind, AssetTier.SYSTEMIC, "$material ($face)", prompt)
    }

    private fun shapeWords(family: PropSilhouette): String = when (family) {
        PropSilhouette.CANOPY -> "a living tree with a trunk and a broad leafy crown"
        PropSilhouette.SPIRE -> "a tall narrow tree or standing stone"
        PropSilhouette.FROND -> "a clump of tall grass, reeds or leaves growing from the ground"
        PropSilhouette.BOULDER -> "a low rock or boulder"
        PropSilhouette.SHRINE -> "a small carved shrine or altar"
        PropSilhouette.CRYSTAL -> "a jagged crystal formation growing from the ground"
        PropSilhouette.BRAZIER -> "a standing brazier with fire burning in it"
        PropSilhouette.SIGIL -> "a carved standing marker or banner"
    }

    private fun sprite(direction: ArtDirection, block: BlockType, setting: String, kit: BiomeArtKit?): ForgeOrder {
        val subject = block.displayName
        val prompt = buildString {
            append("A single ")
            append(subject.lowercase())
            // The shape family, in plain words. A block's name alone misleads:
            // "Iroko Canopy" was drawn as a roofed canopy, twice. The family is
            // what the renderer already decided this prop is, so the art and
            // the silhouette the world expects cannot disagree.
            append(", drawn as ")
            append(shapeWords(PropSilhouette.forMaterial(block)))
            append(", as a game prop sprite for an isometric action RPG like Diablo or Hades")
            if (setting.isNotBlank()) append(", belonging to $setting")
            append(". The whole object is visible, centred, upright, seen from a high three-quarter camera angle, ")
            append("with its base at the bottom of the frame. ")
            append("Isolated on a solid $KEY_NAME background that fills everything around the object. ")
            append("No ground plane, no base, no pedestal, no floor tile or platform under it, no cast shadow, no other objects, ")
            append("crisp clean silhouette edges, no magenta or pink anywhere on the object itself. ")
            append(direction.diction.house)
            append(". ")
            append(ArtBible.paletteNote(direction, kit))
            direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(" Style: $it.") }
            append(" ")
            append(direction.diction.forbidden)
            append(".")
        }
        return ForgeOrder("prop:${block.id}", AssetKind.PROP_SPRITE, AssetTier.PROP, subject, prompt)
    }
}
