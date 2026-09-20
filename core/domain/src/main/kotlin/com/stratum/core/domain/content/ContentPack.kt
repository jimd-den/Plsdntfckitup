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
)

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
