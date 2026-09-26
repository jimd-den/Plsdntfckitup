package com.stratum.core.domain.content

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.RarityStyle
import com.stratum.core.domain.item.WeaponBase
import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.faction.FactionDefinition
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.core.domain.tabletop.SkillCheck
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.world.BlockType

/**
 * Everything that makes one world feel different from another: blocks, biomes,
 * classes, lore and palette. The engine ships with no content of its own, so a
 * pack is not a mod bolted onto a game -- it *is* the game.
 *
 * Packs come from three places and the engine treats them identically:
 * a built-in module, an AI generation run, or a file the player imported.
 */
data class ContentPack(
    val id: String,
    val name: String,
    val author: String,
    val version: String = "1.0.0",
    val description: String = "",
    val origin: PackOrigin = PackOrigin.BUILT_IN,
    val palette: PackPalette = PackPalette(),
    val blocks: List<BlockType> = emptyList(),
    val biomes: List<BiomeDefinition> = emptyList(),
    val heroClasses: List<HeroClassDefinition> = emptyList(),
    val loreEntries: List<LoreEntry> = emptyList(),
    val spriteSetIds: List<String> = emptyList(),
    // The action RPG half. A pack that supplies none of these is a world you can
    // dig but not fight in, which is a legitimate thing for a pack to be.
    val damageTypes: List<DamageTypeDefinition> = emptyList(),
    val affixes: List<AffixDefinition> = emptyList(),
    val inserts: List<InsertDefinition> = emptyList(),
    /**
     * How this pack shapes its world. Null leaves whatever an earlier pack set,
     * so a pack that only adds monsters does not flatten somebody else's
     * landscape by saying nothing.
     */
    val terrain: TerrainRecipe? = null,
    val weapons: List<WeaponBase> = emptyList(),
    val enemies: List<EnemyDefinition> = emptyList(),
    val skills: List<SkillDefinition> = emptyList(),
    val rarityStyles: List<RarityStyle> = emptyList(),
    /** Sheets shipped by the pack. Generated sheets join these at runtime. */
    val spriteSheets: List<SpriteSheet> = emptyList(),
    /**
     * Hand-authored levels. A pack whose terrain recipe names
     * [TerrainRecipe.TILE_MAP] plays on one of these instead of generated ground.
     */
    val maps: List<TileMap> = emptyList(),
    /**
     * Tabletop checks: dice against a difficulty, paid out as boons in the
     * fight. How a plugin brings a pen-and-paper system into the game.
     */
    val checks: List<SkillCheck> = emptyList(),
    /**
     * Passive skill trees. The last one loaded is the one characters grow
     * on; a pack with combat but no tree gets a generated one.
     */
    val passiveTrees: List<PassiveTree> = emptyList(),
    /**
     * Crafting currency, support gems and waystone mods. A pack with combat
     * that defines none of a kind gets the engine's standard set of it.
     */
    val currencies: List<CurrencyDefinition> = emptyList(),
    val supports: List<SupportDefinition> = emptyList(),
    val waystoneMods: List<WaystoneMod> = emptyList(),
    /** The sides in the world, and how they regard each other. */
    val factions: List<FactionDefinition> = emptyList(),
    /** Groups of monsters that spawn and fight together. */
    val enemyPacks: List<EnemyPackDefinition> = emptyList(),
    /** Kinds of town the world generator may build. */
    val settlements: List<SettlementRecipe> = emptyList(),
) {
    val blockCount: Int get() = blocks.size

    fun blockOrNull(id: String): BlockType? = blocks.firstOrNull { it.id == id }

    /** A pack with no blocks and no biomes cannot generate a world. */
    val isPlayable: Boolean get() = blocks.isNotEmpty() && biomes.isNotEmpty()

    /** Whether there is anything to fight and anything to fight it with. */
    val hasCombat: Boolean get() = enemies.isNotEmpty() && weapons.isNotEmpty()
}

enum class PackOrigin { BUILT_IN, AI_GENERATED, IMPORTED }

/**
 * The colours the interface and the world both draw from, so an imported pack
 * restyles the HUD as well as the terrain.
 */
data class PackPalette(
    val surface: Long = 0xFF12131A,
    val surfaceRaised: Long = 0xFF1C1E28,
    val ink: Long = 0xFFF2EFE6,
    val inkMuted: Long = 0xFF9A96A8,
    val accent: Long = 0xFFD9A441,
    val accentAlt: Long = 0xFF5AC8B0,
    val danger: Long = 0xFFD2544B,
)

/**
 * A region of the world. The generator reads only the numbers; the names and
 * lore are the pack's business.
 */
data class BiomeDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** Block laid on the surface, e.g. grass or ash. */
    val surfaceBlockId: String,
    /** The few layers directly beneath the surface. */
    val subsurfaceBlockId: String,
    /** Everything down to bedrock. */
    val bedrockFillerBlockId: String,
    /** Added to the base terrain height, letting a biome sit high or low. */
    val heightBias: Int = 0,
    /** Multiplies [WorldConfig.surfaceVariation]; 0 gives a flat plain. */
    val roughness: Float = 1f,
    /** Blocks scattered on the surface with their spawn chance, e.g. trees. */
    val scatter: List<ScatterRule> = emptyList(),
    /** Ores and their depth bands. */
    val deposits: List<DepositRule> = emptyList(),
    val ambientLight: Int = 12,
    /** How the region is laid out beyond its noise. See [BiomeComposition]. */
    val composition: BiomeComposition = BiomeComposition(),
)

/**
 * The deliberate part of a region's layout: where people walk and what they
 * built.
 *
 * Noise alone makes a texture, not a place. Diablo's overworld reads as
 * designed because it is: roads wind between set pieces, and set pieces sit in
 * clearings the scenery frames. A pack says which blocks a region's paths and
 * landmarks are made of; the generator decides where they go, deterministically
 * and one column at a time, so chunks still generate in any order.
 */
data class BiomeComposition(
    /** Surface block of the paths that wind through the region; null for none. */
    val pathBlockId: String? = null,
    /** Rough path width, in blocks. */
    val pathWidth: Float = 2f,
    /** A set piece placed in open ground every so often; null for none. */
    val landmark: Landmark? = null,
)

/**
 * A small built place: a centrepiece, a ring around it, a floor under both.
 *
 * The ground under it is levelled, scenery is kept back from it, and it only
 * stands in a clearing, so it is always something the player walks up to
 * rather than something found half-buried in a thicket.
 */
data class Landmark(
    val centreBlockId: String,
    val ringBlockId: String? = null,
    val ringRadius: Int = 3,
    val ringCount: Int = 4,
    /** A floor-shaped block laid around the centre; null for bare ground. */
    val floorBlockId: String? = null,
    val floorRadius: Int = 3,
    /**
     * Share of candidate sites that get one. Sites sit on a fixed world grid
     * about three screens apart, so this is how often a region has a set
     * piece rather than how far apart they are.
     */
    val chance: Float = 0.7f,
    /** Kept free of scatter around the centre. */
    val clearRadius: Int = 6,
) {
    init {
        require(clearRadius in 1..MAX_CLEAR_RADIUS) { "A landmark's clearing must be 1..$MAX_CLEAR_RADIUS blocks, not $clearRadius" }
        require(ringRadius <= clearRadius && floorRadius <= clearRadius) { "A landmark must fit inside its clearing" }
        require(chance in 0f..1f) { "Landmark chance of $chance is not a share" }
    }

    companion object {
        /** The largest clearing the generator's site grid can hold without neighbours overlapping. */
        const val MAX_CLEAR_RADIUS = 10
    }
}

data class ScatterRule(
    val blockId: String,
    /** 0..1 chance per surface column. */
    val chance: Float,
    /** Stacked height, so a 4 makes a tree trunk rather than a shrub. */
    val height: Int = 1,
    /**
     * Placed on top of the stack instead of one more [blockId].
     *
     * This is what makes a tree a tree: three trunk blocks and a canopy, rather
     * than a four-block pillar of bark that the renderer has no way to tell
     * apart from a post.
     */
    val capBlockId: String? = null,
)

data class DepositRule(
    val blockId: String,
    val minZ: Int,
    val maxZ: Int,
    /** 0..1 chance per eligible cell, scaled by `WorldConfig.oreRichness`. */
    val chance: Float,
    /** Blobs of this many blocks cluster together rather than scattering as single cells. */
    val clusterSize: Int = 4,
)

/** A playable class. Kept deliberately thin: packs describe, the engine resolves. */
data class HeroClassDefinition(
    val id: String,
    val name: String,
    val title: String = "",
    val description: String = "",
    val baseHealth: Int = 100,
    val baseResource: Int = 50,
    val resourceName: String = "Focus",
    val strength: Int = 10,
    val agility: Int = 10,
    val insight: Int = 10,
    val startingBlockIds: List<String> = emptyList(),
    val abilityIds: List<String> = emptyList(),
    val spriteSetId: String? = null,
    /** Combat baseline before gear and levels. */
    val baseStats: CombatStats = CombatStats(),
    /** The weapon the class starts holding. */
    val startingWeaponId: String? = null,
) {
    /**
     * Health lives on both [baseHealth] and [baseStats] because packs wrote the
     * former first. The stats block is the one combat reads, so it takes
     * [baseHealth] when it has not been given its own.
     */
    val resolvedStats: CombatStats
        get() = if (baseStats.maxHealth > 0) baseStats else baseStats.copy(maxHealth = baseHealth)
}

/** A codex entry. AI lore generation writes these; the codex screen reads them. */
data class LoreEntry(
    val id: String,
    val title: String,
    val body: String,
    val category: LoreCategory = LoreCategory.HISTORY,
    /** Ties an entry to a biome, block or class so it can surface in context. */
    val subjectId: String? = null,
)

enum class LoreCategory { HISTORY, DEITY, ARTIFACT, BESTIARY, PLACE, RITUAL }
