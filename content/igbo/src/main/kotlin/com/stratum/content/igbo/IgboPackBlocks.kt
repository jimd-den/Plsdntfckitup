package com.stratum.content.igbo

import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType

/**
 * Blocks for the built-in pack.
 *
 * This module is pure data. It depends on `:core:domain` and nothing else, and
 * no engine code depends on it -- which is the point: deleting this module
 * leaves a working engine with nothing to show, not a broken build.
 */
internal object IgboPackBlocks {

    private const val NS = "igbo"

    val redEarth = BlockType(
        id = "$NS:red_earth",
        displayName = "Laterite Earth",
        material = BlockMaterial.SOIL,
        hardness = 0.6f,
        topColor = 0xFF8C4A2F,
        sideColor = 0xFF6B3722,
        accentColor = 0xFFB35C39,
    )

    val groveTurf = BlockType(
        id = "$NS:grove_turf",
        displayName = "Grove Turf",
        material = BlockMaterial.SOIL,
        hardness = 0.5f,
        topColor = 0xFF2E7D32,
        sideColor = 0xFF5D4033,
        accentColor = 0xFF76FF03,
    )

    val riverClay = BlockType(
        id = "$NS:river_clay",
        displayName = "Benue River Clay",
        material = BlockMaterial.SOIL,
        hardness = 0.7f,
        hasGravity = true,
        topColor = 0xFF5A6B57,
        sideColor = 0xFF3D4A3B,
    )

    val ashSand = BlockType(
        id = "$NS:ash_sand",
        displayName = "Thunder Ash",
        material = BlockMaterial.SOIL,
        hardness = 0.4f,
        hasGravity = true,
        topColor = 0xFF4A4A55,
        sideColor = 0xFF33333C,
    )

    val graniteStone = BlockType(
        id = "$NS:granite",
        displayName = "Udi Granite",
        material = BlockMaterial.STONE,
        hardness = 2.2f,
        requiredTier = 1,
        topColor = 0xFF6E6A63,
        sideColor = 0xFF4E4B46,
    )

    val obsidianCrag = BlockType(
        id = "$NS:obsidian_crag",
        displayName = "Storm-struck Obsidian",
        material = BlockMaterial.STONE,
        hardness = 5f,
        requiredTier = 2,
        topColor = 0xFF1B2029,
        sideColor = 0xFF0F1319,
        accentColor = 0xFF00E5FF,
    )

    val catacombMasonry = BlockType(
        id = "$NS:catacomb_masonry",
        displayName = "Igbo-Ukwu Masonry",
        material = BlockMaterial.STONE,
        hardness = 3f,
        requiredTier = 1,
        topColor = 0xFF3D3220,
        sideColor = 0xFF2A2317,
        accentColor = 0xFFFFB300,
    )

    val bronzeOre = BlockType(
        id = "$NS:bronze_ore",
        displayName = "Bronze Ore",
        glyph = "🔶",
        glyphScale = 0.8f,
        material = BlockMaterial.ORE,
        hardness = 3.5f,
        requiredTier = 1,
        dropId = "$NS:bronze_ingot",
        topColor = 0xFF7A6A4F,
        sideColor = 0xFF574B38,
        accentColor = 0xFFCD7F32,
    )

    val ironOre = BlockType(
        id = "$NS:iron_ore",
        displayName = "Bog Iron",
        glyph = "⚫\uFE0F",
        glyphScale = 0.7f,
        material = BlockMaterial.ORE,
        hardness = 3f,
        requiredTier = 1,
        dropId = "$NS:iron_ingot",
        topColor = 0xFF6B625C,
        sideColor = 0xFF4A443F,
        accentColor = 0xFFB0A89E,
    )

    val stormCrystal = BlockType(
        id = "$NS:storm_crystal",
        displayName = "Amadioha Crystal",
        glyph = "💎",
        material = BlockMaterial.ORE,
        hardness = 6f,
        requiredTier = 3,
        lightEmission = 9,
        topColor = 0xFF1E3A4A,
        sideColor = 0xFF152A36,
        accentColor = 0xFF00E5FF,
    )

    val irokoTrunk = BlockType(
        id = "$NS:iroko_trunk",
        displayName = "Iroko Trunk",
        material = BlockMaterial.WOOD,
        hardness = 1.4f,
        topColor = 0xFF5B3A21,
        sideColor = 0xFF432B18,
    )

    val irokoCanopy = BlockType(
        id = "$NS:iroko_canopy",
        displayName = "Iroko Canopy",
        glyph = "🌳",
        glyphScale = 1.9f,
        material = BlockMaterial.FOLIAGE,
        hardness = 0.3f,
        isOpaque = false,
        topColor = 0xFF1F5E23,
        sideColor = 0xFF184A1C,
    )

    val palmReed = BlockType(
        id = "$NS:palm_reed",
        displayName = "Raffia Reed",
        glyph = "🌾",
        glyphScale = 0.85f,
        material = BlockMaterial.FOLIAGE,
        hardness = 0.2f,
        isSolid = false,
        isOpaque = false,
        needsSupport = true,
        topColor = 0xFF6B8E23,
        sideColor = 0xFF556B1A,
    )

    val spiritWater = BlockType(
        id = "$NS:spirit_water",
        displayName = "Spirit Water",
        glyph = "🌊",
        material = BlockMaterial.LIQUID,
        hardness = BlockType.UNBREAKABLE,
        isSolid = false,
        isOpaque = false,
        lightEmission = 3,
        topColor = 0x9900B0FF,
        sideColor = 0x8800829B,
    )

    val bronzeBrazier = BlockType(
        id = "$NS:bronze_brazier",
        displayName = "Bronze Brazier",
        glyph = "🔥",
        glyphScale = 1.2f,
        material = BlockMaterial.METAL,
        hardness = 1.5f,
        isSolid = false,
        isOpaque = false,
        needsSupport = true,
        lightEmission = 14,
        topColor = 0xFFCD7F32,
        sideColor = 0xFF8C5522,
        accentColor = 0xFFFFB300,
    )

    val nsibidiSeal = BlockType(
        id = "$NS:nsibidi_seal",
        displayName = "Nsibidi Seal",
        material = BlockMaterial.RITUAL,
        hardness = BlockType.UNBREAKABLE,
        lightEmission = 6,
        topColor = 0xFF2A2117,
        sideColor = 0xFF1C160F,
        accentColor = 0xFFFFD700,
    )

    val ofoShrine = BlockType(
        id = "$NS:ofo_shrine",
        displayName = "Ofo Shrine",
        material = BlockMaterial.RITUAL,
        hardness = 8f,
        requiredTier = 2,
        lightEmission = 11,
        topColor = 0xFF6B4A22,
        sideColor = 0xFF4A3217,
        accentColor = 0xFFFFD700,
    )

    val all = listOf(
        redEarth, groveTurf, riverClay, ashSand,
        graniteStone, obsidianCrag, catacombMasonry,
        bronzeOre, ironOre, stormCrystal,
        irokoTrunk, irokoCanopy, palmReed, spiritWater,
        bronzeBrazier, nsibidiSeal, ofoShrine,
    )
}
