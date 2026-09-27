package com.stratum.core.domain.art

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockType

/**
 * The seam between "what the world is" and "what the world looks like".
 *
 * Before this existed the renderer answered both questions. It decided that
 * ground eight levels down is fifty-five per cent as bright, that a ledge casts
 * at twenty-two per cent black, that the player is a cream circle with a bronze
 * ring — and it decided them in constants, for every pack, forever. A content
 * pack could restyle its blocks but could not restyle its *world*, and a player
 * could not restyle anything at all.
 *
 * A director answers only the second question, from data, per block, per prop,
 * per actor, per biome. The renderer keeps the questions it is actually good
 * at: where things are on screen, what order to draw them in, and how to get
 * the pixels out fast. It never again gets an opinion about what a sacred grove
 * looks like.
 */
interface WorldArtDirector {

    /** The style this director is working from, for anything that needs the whole picture. */
    val direction: ArtDirection

    /** How one cube face is coloured, given where it is and what is around it. */
    fun terrainStyleFor(cue: TerrainCue): TerrainStyle

    /** How a prop is drawn, or null for a block that is not one. */
    fun propStyleFor(cue: PropCue): PropStyle?

    /** How an actor is weighted against the ground it stands on. */
    fun actorStyleFor(actor: ActorPresentation): ActorStyle

    /** The air, the sky and the weather of a place at a time. */
    fun atmosphereFor(biome: BiomeDefinition?, time: WorldTime): AtmosphereStyle

    /** What a moment of combat throws onto the screen. */
    fun effectsFor(cue: CombatCue): List<VisualEffect>
}

/** Where a block is, relative to the camera and its neighbours. */
data class TerrainCue(
    val block: BlockType,
    val biomeId: String? = null,
    /** True for the block at the top of its column: the one the player walks on. */
    val isTop: Boolean = true,
    /** How many levels below the camera's own level this column sits. */
    val depthBelowEye: Int = 0,
    /** A taller neighbour to the north or west casts onto this cell. */
    val ledgeShadow: Boolean = false,
    /** Both of them do, so this is an inside corner. */
    val cornerShadow: Boolean = false,
    /** Propagated block light at this cell, 0..15. */
    val lightLevel: Int = 15,
    /** Strongest emissive contribution reaching this cell, 0..1. */
    val emissive: Float = 0f,
    /** Colour of that contribution. */
    val emissiveColor: Long = 0x00000000,
    /** 0 at the camera, 1 at the edge of sight. Drives haze. */
    val distance: Float = 0f,
    /** Stable per-cell value in 0..1, so grain does not crawl when the camera moves. */
    val grain: Float = 0.5f,
)

/** The three faces of one cube, already lit, shaded, hazed and quantised. */
data class TerrainStyle(
    val top: Long,
    val left: Long,
    val right: Long,
    /** Drawn over the top face, or transparent. Carries ledge and corner occlusion. */
    val occlusion: Long = 0x00000000,
    /** The cell seam, which is what makes a field of tiles read as diggable cells. */
    val seam: Long = 0x00000000,
    /** A contour on the terrace edge, or transparent when the style wants none. */
    val edge: Long = 0x00000000,
    val edgeWidth: Float = 0f,
)

/** A block that stands on the ground rather than being the ground. */
data class PropCue(
    val block: BlockType,
    val biomeId: String? = null,
    /** Stable per-instance value, so one tree is not every tree. */
    val variant: Int = 0,
    val depthBelowEye: Int = 0,
    val distance: Float = 0f,
    val lightLevel: Int = 15,
)

/**
 * How one prop is drawn.
 *
 * [silhouette] is the important field. The screenshot's forest is the same
 * emoji two hundred times, which the eye reads as a repeating tile rather than
 * as trees; a family plus a variant index gives a shape that differs every time
 * it is placed while still belonging to the same world.
 */
data class PropStyle(
    val silhouette: PropSilhouette,
    val variant: Int,
    /** The lit body colour. */
    val fill: Long,
    /** The shadowed side, for props drawn with two planes. */
    val shade: Long,
    /** Trunk, stem or base, where the family has one. */
    val support: Long,
    val outline: Long,
    val outlineWidth: Float,
    /** Multiplier on the style's base prop size. */
    val scale: Float,
    /** Opacity of the ellipse that plants the prop on the ground. */
    val contactShadow: Float,
    /** Glow for props that emit light, or transparent. */
    val glow: Long = 0x00000000,
    /** Kept so a renderer without silhouettes can still draw something. */
    val glyph: String? = null,
)

/**
 * The shapes a prop can be.
 *
 * A closed set, and a short one. This is the shape half of the art bible: five
 * families with variation reads as a place, where free-form generated shapes
 * read as a clip-art bin. Anything a pack invents is mapped onto one of these
 * by [PropSilhouette.forMaterial], so an unknown block still gets a sensible
 * shape rather than nothing.
 */
enum class PropSilhouette {
    /** Broad canopy over a tapered trunk. Forests. */
    CANOPY,
    /** Tall and narrow. Conifers, cypresses, standing stones. */
    SPIRE,
    /** Clustered blades from a common base. Reeds, grass, crops. */
    FROND,
    /** Low, wide, irregular. Boulders, rubble, shrubs. */
    BOULDER,
    /** Built, symmetrical, with a plinth. Shrines, altars, idols. */
    SHRINE,
    /** Faceted and emissive. Crystals, ritual growths. */
    CRYSTAL,
    /** A bowl on a stand with a flame. Braziers, lanterns, torches. */
    BRAZIER,
    /** A flat marker, standing upright. Seals, banners, signs. */
    SIGIL;

    companion object {
        /**
         * The shape family a block should be drawn as.
         *
         * A pack never states this, and requiring it would mean no pack a model
         * generated could ever be drawn. So it is read out of what packs *do*
         * say. The glyph comes first, because a pack that wrote 🌳 has told us
         * more about the shape than any material enum can: material says what a
         * thing is made of, and a bronze brazier and a bronze statue are the
         * same material and very different silhouettes.
         *
         * Wrong occasionally and blank never. Wrong-but-present is what lets a
         * pack be played the minute it finishes generating, and a pack that
         * cares can override the whole mapping through its [BiomeArtKit].
         */
        fun forMaterial(block: BlockType): PropSilhouette =
            forGlyph(block.glyph) ?: fromMaterial(block)

        /**
         * What a pack's own glyph says the shape is.
         *
         * Matched on the first character so variation selectors and skin-tone
         * modifiers do not turn a known glyph into an unknown one.
         */
        fun forGlyph(glyph: String?): PropSilhouette? {
            val first = glyph?.takeIf { it.isNotEmpty() }?.first() ?: return null
            return GLYPHS.entries.firstOrNull { (key, _) -> key.first() == first }?.value
        }

        private fun fromMaterial(block: BlockType): PropSilhouette = when (block.material.name) {
            "FOLIAGE" ->
                // Both a canopy tree and a clump of reeds are foliage, and the
                // only thing a pack has said about the difference is how big it
                // asked for the thing to be drawn. A block that says nothing is
                // a tree, because that is what most scattered foliage is.
                if (block.glyphScale < FROND_SCALE) FROND else CANOPY
            "WOOD" -> SPIRE
            "CLOTH" -> SIGIL
            // A lit ritual object is a flame; an unlit one is something built.
            "RITUAL" -> if (block.lightEmission > 0) BRAZIER else SHRINE
            "METAL" -> if (block.lightEmission > 0) BRAZIER else SHRINE
            "ORE" -> CRYSTAL
            "LIQUID" -> FROND
            else -> if (block.lightEmission > 0) CRYSTAL else BOULDER
        }

        /** Below this glyph scale a piece of foliage is a clump rather than a tree. */
        private const val FROND_SCALE = 0.9f

        /**
         * Glyphs a pack is likely to reach for, and the shape each one means.
         *
         * Not exhaustive and not meant to be: anything unlisted falls through
         * to the material, which always answers.
         */
        private val GLYPHS: Map<String, PropSilhouette> = mapOf(
            "🌳" to CANOPY, "🌴" to CANOPY, "🌵" to SPIRE, "🌲" to SPIRE, "🎄" to SPIRE,
            "🌾" to FROND, "🌿" to FROND, "☘" to FROND, "🍀" to FROND, "🌱" to FROND,
            "🌊" to FROND, "🪷" to FROND, "🌸" to FROND, "🌻" to FROND, "🌷" to FROND,
            "🔥" to BRAZIER, "🕯" to BRAZIER, "🏮" to BRAZIER, "🪔" to BRAZIER,
            "💎" to CRYSTAL, "🔮" to CRYSTAL, "🔶" to CRYSTAL, "🔸" to CRYSTAL,
            "⚫" to BOULDER, "🪨" to BOULDER, "🗿" to SHRINE, "⛩" to SHRINE, "🏛" to SHRINE,
            "🪧" to SIGIL, "📜" to SIGIL, "🚩" to SIGIL, "🏴" to SIGIL,
        )
    }
}

/** What an actor is, as far as the look of it is concerned. */
data class ActorPresentation(
    val id: String,
    val role: ActorRole,
    val rank: EnemyRank? = null,
    /** The pack or class accent, when the actor has one of its own. */
    val accent: Long? = null,
    /** 0..1, how recently it was hit. */
    val flash: Float = 0f,
    /** 0..1, how hard it is being shoved. */
    val impact: Float = 0f,
    val isInvulnerable: Boolean = false,
    val isRolling: Boolean = false,
    /** Levels below the camera, so distant actors shade with their ground. */
    val depthBelowEye: Int = 0,
)

/**
 * The classes of thing that get drawn on top of the terrain.
 *
 * The contrast contract is written per class, not per entity: "the player wins,
 * threats come second, treasure pulls the eye, scenery never competes" is a
 * rule about categories, and stating it as one is what stops it eroding the
 * next time somebody adds a monster.
 */
enum class ActorRole {
    PLAYER,
    ENEMY,
    ALLY,
    LOOT,
    INTERACTABLE,
    LANDMARK,
}

/** Everything the renderer needs to make an actor beat the ground it stands on. */
data class ActorStyle(
    /** Multiplier on the actor's literal grid size. Deliberately above one. */
    val scale: Float,
    val body: Long,
    val outline: Long,
    val outlineWidth: Float,
    /** The lit edge along the top, which is what lifts a body off its background. */
    val rim: Long,
    val rimStrength: Float,
    val contactShadow: Float,
    /** Ring on the ground under elites, champions and bosses, or transparent. */
    val groundRing: Long = 0x00000000,
    /** Pull-me halo under loot and interactables, or transparent. */
    val halo: Long = 0x00000000,
    /** Wash over the body while it is being hit. */
    val flashTint: Long = 0x00000000,
)

/** Time of day, as a share of a full cycle, plus whatever the world is doing. */
data class WorldTime(
    /** 0 at dawn, 0.5 at dusk, wrapping. */
    val dayFraction: Float = 0.25f,
    /** Seconds since the world started, for anything that drifts. */
    val elapsedSeconds: Float = 0f,
    /** True underground, where the sky is not the light source. */
    val isUnderground: Boolean = false,
)

/** The air of a place, resolved for one frame. */
data class AtmosphereStyle(
    val skyTop: Long,
    val skyBottom: Long,
    val haze: Long,
    val hazeStrength: Float,
    val vignette: Float,
    val overlay: Long,
    val moteKind: MoteKind,
    val moteDensity: Int,
    val moteColor: Long,
    val moteDrift: Float,
)

/** A moment of combat, described without reference to any engine type. */
data class CombatCue(
    val kind: CombatMoment,
    /** Damage type or skill colour, when the event has one. */
    val color: Long = 0xFFFFFFFF,
    /** 0..1, how big this one was relative to a normal hit. */
    val emphasis: Float = 0.5f,
    val onPlayer: Boolean = false,
)

enum class CombatMoment {
    /** A weapon swung, whether or not it connects. */
    SWING,
    HIT,
    CRITICAL,
    BLOCKED,
    DODGED,
    KILL,
    HEAL,
    CAST,
    DASH,
    LOOT_DROP,
    LEVEL_UP,
}

/**
 * One piece of combat theatre.
 *
 * Effects are returned as data rather than drawn by the director for the same
 * reason styles are: a list of rings, flashes and shakes can be asserted on in
 * a test, replayed from a seed, and drawn by whichever backend is on screen.
 * It is also where most of the perceived quality of a fight actually lives — a
 * dash that leaves a smear and an impact that rings and shakes reads better
 * than any amount of extra animation frames on the body.
 */
data class VisualEffect(
    val kind: EffectKind,
    val color: Long,
    /** Tiles, for the effects that have an extent. */
    val radius: Float = 1f,
    val duration: Float = 0.3f,
    /** 0..1, feeds whatever the effect's strength means. */
    val intensity: Float = 1f,
)

enum class EffectKind {
    /** An expanding ring on the ground at the point of impact. */
    IMPACT_RING,
    /** A white-hot wash over the struck body. */
    HIT_FLASH,
    /** A short arc following the weapon through its swing. */
    WEAPON_ARC,
    /** Trailing copies of the body, which is what sells speed. */
    AFTERIMAGE,
    /** A brief camera shove. */
    SCREEN_SHAKE,
    /** Thrown fragments. */
    DEBRIS,
    /** A held glow at the actor's feet. */
    GROUND_DECAL,
    /** A rising column, for kills and level-ups. */
    BEAM,
    /** Floating text. */
    NUMBER,
}
