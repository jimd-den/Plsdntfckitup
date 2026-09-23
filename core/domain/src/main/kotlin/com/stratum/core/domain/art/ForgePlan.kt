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

    /**
     * Small things lying on the ground — leaves, pebbles, flowers, roots —
     * seen from above, cut out, and laid flat across the floor. Cleaned up
     * like a sprite; drawn like a decal.
     */
    GROUND_DETAIL,
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
        /** Also order one still sprite per hero class and per monster. */
        includeActors: Boolean = true,
        /**
         * How many individuals of each prop and each ground to paint.
         *
         * One of each is a world of clones: a forest of the identical tree and
         * a field of the identical grass is the tell that nobody made it.
         * Three is enough that no two neighbours need match.
         */
        variants: Int = DEFAULT_VARIANTS,
    ): List<ForgeOrder> {
        val blocks = pack.blocks.associateBy { it.id }
        val orders = LinkedHashMap<String, ForgeOrder>()
        fun add(order: ForgeOrder) { orders.putIfAbsent(order.key, order) }

        val kits = BiomeArtKit.deriveAll(pack)
        pack.biomes.filter { biomeIds.isEmpty() || it.id in biomeIds }.forEach { biome ->
            val kit = kits[biome.id]
            // The region's name, never its description. Descriptions are
            // written to sell a place ("mossy flagstones, thick with emerald
            // spirit mist") and a model paints every word of that into every
            // tile and every tree.
            val setting = biome.name
            blocks[biome.surfaceBlockId]?.let { surface ->
                add(tile(direction, surface, AssetKind.GROUND_TILE, setting, kit))
                add(tile(direction, surface, AssetKind.WALL_TILE, setting, kit))
                for (v in 1 until variants) add(tile(direction, surface, AssetKind.GROUND_TILE, setting, kit, v))
            }
            for (v in 0 until DETAILS_PER_REGION) add(detail(direction, biome.id, setting, kit, v))
            blocks[biome.subsurfaceBlockId]?.let {
                add(tile(direction, it, AssetKind.WALL_TILE, setting, kit))
                // Dug out, built on, trodden into paths: the layer under the
                // surface is the ground a player's own work stands on.
                add(tile(direction, it, AssetKind.GROUND_TILE, setting, kit))
            }
            blocks[biome.bedrockFillerBlockId]?.let { add(tile(direction, it, AssetKind.WALL_TILE, setting, kit)) }
            // Whatever the region's paths and landmarks are made of: these are
            // the places a player is led to, so they are never left flat.
            val composition = biome.composition
            val built = listOfNotNull(
                composition.pathBlockId, composition.landmark?.centreBlockId,
                composition.landmark?.ringBlockId, composition.landmark?.floorBlockId,
            ).mapNotNull { blocks[it] }.distinct()
            built.forEach { block ->
                when {
                    block.glyph != null -> add(sprite(direction, block, setting, kit))
                    block.shape == BlockShape.FLOOR -> add(tile(direction, block, AssetKind.GROUND_TILE, "a laid, built floor of fitted pieces", null))
                    else -> {
                        add(tile(direction, block, AssetKind.GROUND_TILE, setting, kit))
                        add(tile(direction, block, AssetKind.WALL_TILE, setting, kit))
                    }
                }
            }
            biome.scatter.forEach { rule ->
                val scattered = listOfNotNull(blocks[rule.blockId], rule.capBlockId?.let { blocks[it] })
                scattered.forEach { block ->
                    for (v in 0 until variants) add(sprite(direction, block, setting, kit, v))
                }
            }
        }
        // What the player builds with is part of the world's look whichever
        // region they build it in.
        pack.blocks.filter { it.shape == BlockShape.WALL }.forEach { wall ->
            add(tile(direction, wall, AssetKind.WALL_TILE, "a built compound wall", null))
            add(tile(direction, wall, AssetKind.GROUND_TILE, "the top of a built compound wall", null))
        }
        pack.blocks.filter { it.shape == BlockShape.FLOOR }.forEach { floor ->
            add(tile(direction, floor, AssetKind.GROUND_TILE, "a laid, built floor of fitted pieces", null))
        }
        // Light sources are the props a player looks at most.
        // Liquids are surfaces, not objects: asked for as a sprite, "spirit
        // water" came back as a potion bottle.
        pack.blocks
            .filter { it.glyph != null && it.lightEmission > 0 && it.material != BlockMaterial.LIQUID }
            .forEach { add(sprite(direction, it, "", null)) }
        if (includeActors) {
            pack.heroClasses.forEach { hero ->
                add(actor(direction, "actor:${hero.id}", hero.name, "${hero.title}. ${hero.description}", AssetTier.HERO, hero = true))
            }
            pack.enemies.forEach { enemy ->
                add(actor(direction, "actor:${enemy.id}", enemy.name, enemy.description, AssetTier.PROP, hero = false))
            }
        }
        return orders.values.toList()
    }

    /**
     * One character, standing, cut out.
     *
     * A still, not an animation: it is what an actor looks like until someone
     * forges it a proper sheet, and it is already far more of a character than
     * the stand-in body. Drawn facing down and to the right, the way this
     * camera sees a character walking towards it; the renderer mirrors it for
     * the other way.
     */
    private fun actor(direction: ArtDirection, key: String, name: String, about: String, tier: AssetTier, hero: Boolean): ForgeOrder {
        val prompt = buildString {
            append("A single full-body game character sprite for an isometric action RPG like Diablo or Hades: ")
            append(name)
            if (about.isNotBlank()) append(" — ").append(about.trim().trimEnd('.')).append('.')
            append(if (hero) " The player's hero: heroic, readable, strong silhouette. " else " A hostile monster: menacing, readable silhouette. ")
            append("Standing in a ready stance, whole body visible from head to feet, seen from a high three-quarter camera, ")
            append("turned towards the lower right of the frame, feet at the bottom of the frame. ")
            append("Isolated on a solid $KEY_NAME background that fills everything around the character. ")
            append("No ground, no base, no shadow, no other figures, no text, crisp clean silhouette edges, ")
            append("no magenta or pink anywhere on the character. ")
            append(direction.diction.house)
            append(". ")
            append(ArtBible.paletteNote(direction, null))
            direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(" Style: $it.") }
            append(" ")
            append(direction.diction.forbidden)
            append(".")
        }
        return ForgeOrder(key, AssetKind.PROP_SPRITE, tier, name, prompt)
    }

    private fun tile(
        direction: ArtDirection,
        block: BlockType,
        kind: AssetKind,
        setting: String,
        kit: BiomeArtKit?,
        variant: Int = 0,
    ): ForgeOrder {
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
            if (setting.isNotBlank()) append(", in $setting")
            if (variant > 0) append(". ").append(GROUND_VARIATIONS[(variant - 1) % GROUND_VARIATIONS.size])
            append(". The texture fills the entire square frame edge to edge and wraps seamlessly on all four sides. ")
            if (kind == AssetKind.GROUND_TILE) append(ArtBible.FLOOR_RULE).append(". ")
            append("Hand-painted stylized action RPG surface, broad soft brush strokes, subtle large-scale variation, ")
            append("even flat lighting with no directional shadows, no objects, no horizon, no perspective, no border, no vignette. ")
            append(ArtBible.SCENERY_RULE).append(". ")
            append(ArtBible.paletteNote(direction, kit, accent = false))
            direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(" Style: $it.") }
            append(" ")
            append(direction.diction.forbidden)
            append(".")
        }
        return ForgeOrder(variantKey("${block.id}/$face", variant), kind, AssetTier.SYSTEMIC, "$material ($face)", prompt)
    }

    /** A handful of small things lying on a region's floor, seen from above. */
    private fun detail(direction: ArtDirection, biomeId: String, setting: String, kit: BiomeArtKit?, variant: Int): ForgeOrder {
        val subject = DETAIL_SUBJECTS[variant % DETAIL_SUBJECTS.size]
        val prompt = buildString {
            append("A small loose cluster of $subject lying on the ground")
            if (setting.isNotBlank()) append(", as found in $setting")
            append(", seen from directly above, flat, as a ground-clutter decal for an isometric action RPG like Diablo or Hades. ")
            append("Isolated on a solid $KEY_NAME background that fills everything around the cluster; no ground texture, ")
            append("no soil or grass background, no shadow, no magenta or pink in the objects. ")
            append("Muted, low contrast, colours close to the ground they lie on, so they read as texture rather than as objects. ")
            append(ArtBible.SCENERY_RULE).append(". ")
            append(direction.diction.house)
            append(". ")
            append(ArtBible.paletteNote(direction, kit, accent = false))
            direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(" Style: $it.") }
            append(" ")
            append(direction.diction.forbidden)
            append(".")
        }
        return ForgeOrder(variantKey("detail:$biomeId", variant), AssetKind.GROUND_DETAIL, AssetTier.SYSTEMIC, subject, prompt)
    }

    /** Variant 0 keeps the plain key, so a kit forged before variants existed still loads. */
    fun variantKey(key: String, variant: Int): String = if (variant == 0) key else "$key#$variant"

    const val DEFAULT_VARIANTS = 3
    const val DETAILS_PER_REGION = 4

    private val GROUND_VARIATIONS = listOf(
        "A variation of the same ground: more bare earth showing through in broad soft trodden patches",
        "A variation of the same ground: slightly darker and denser growth, in broad soft patches",
    )

    private val DETAIL_SUBJECTS = listOf(
        "fallen leaves and small twigs",
        "scattered pebbles and small flat stones",
        "tiny wildflowers and grass tufts",
        "exposed roots, moss and a mushroom or two",
    )

    private val PROP_VARIATIONS = listOf(
        "A different individual from the usual one: younger, smaller and more slender, with its own distinct shape.",
        "A different individual from the usual one: older, larger and weathered, with an asymmetric, characterful shape.",
    )

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

    private fun sprite(direction: ArtDirection, block: BlockType, setting: String, kit: BiomeArtKit?, variant: Int = 0): ForgeOrder {
        val subject = block.displayName
        val prompt = buildString {
            if (variant > 0) append(PROP_VARIATIONS[(variant - 1) % PROP_VARIATIONS.size]).append(' ')
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
            // Light sources are the one kind of scenery allowed to glow: a lit
            // brazier is a landmark, and the player needs to find it.
            val lit = block.lightEmission > 0
            if (!lit) append(ArtBible.SCENERY_RULE).append(". ")
            append(direction.diction.house)
            append(". ")
            append(ArtBible.paletteNote(direction, kit, accent = lit))
            direction.diction.flavour.takeIf(String::isNotBlank)?.let { append(" Style: $it.") }
            append(" ")
            append(direction.diction.forbidden)
            append(".")
        }
        return ForgeOrder(variantKey("prop:${block.id}", variant), AssetKind.PROP_SPRITE, AssetTier.PROP, subject, prompt)
    }
}
