package com.stratum.content.igbo

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.DepositRule
import com.stratum.core.domain.content.ScatterRule

/**
 * The five regions, carried over from the original dungeon biomes and rebuilt
 * as terrain rules the voxel generator can read.
 */
internal object IgboPackBiomes {

    private const val NS = "igbo"

    private val bronzeVein = DepositRule(
        blockId = IgboPackBlocks.bronzeOre.id,
        minZ = 3,
        maxZ = 14,
        chance = 0.10f,
        clusterSize = 4,
    )

    private val ironVein = DepositRule(
        blockId = IgboPackBlocks.ironOre.id,
        minZ = 2,
        maxZ = 11,
        chance = 0.12f,
        clusterSize = 3,
    )

    val sacredGrove = BiomeDefinition(
        id = "$NS:sacred_grove",
        name = "Sacred Grove of Idemili",
        description = "Mossy flagstones under ancient Iroko, thick with emerald spirit mist.",
        surfaceBlockId = IgboPackBlocks.groveTurf.id,
        subsurfaceBlockId = IgboPackBlocks.redEarth.id,
        bedrockFillerBlockId = IgboPackBlocks.graniteStone.id,
        heightBias = 1,
        roughness = 0.7f,
        scatter = listOf(
            ScatterRule(IgboPackBlocks.irokoCanopy.id, chance = 0.055f, height = 1),
            ScatterRule(IgboPackBlocks.palmReed.id, chance = 0.05f, height = 1),
        ),
        deposits = listOf(bronzeVein, ironVein),
        ambientLight = 11,
    )

    val ozoCourtyard = BiomeDefinition(
        id = "$NS:ozo_courtyard",
        name = "Ozo Royal Courtyard",
        description = "Baked terracotta terraces, roped bronze columns and Anyanwu sun seals.",
        surfaceBlockId = IgboPackBlocks.redEarth.id,
        subsurfaceBlockId = IgboPackBlocks.redEarth.id,
        bedrockFillerBlockId = IgboPackBlocks.catacombMasonry.id,
        heightBias = 2,
        roughness = 0.25f,
        scatter = listOf(
            ScatterRule(IgboPackBlocks.nsibidiSeal.id, chance = 0.012f, height = 1),
        ),
        deposits = listOf(bronzeVein.copy(chance = 0.16f)),
        ambientLight = 14,
    )

    val thunderPeak = BiomeDefinition(
        id = "$NS:thunder_peak",
        name = "Amadioha Thunder Altar",
        description = "Shattered obsidian crags charged with storm light.",
        surfaceBlockId = IgboPackBlocks.obsidianCrag.id,
        subsurfaceBlockId = IgboPackBlocks.ashSand.id,
        bedrockFillerBlockId = IgboPackBlocks.graniteStone.id,
        heightBias = 6,
        roughness = 1.5f,
        scatter = emptyList(),
        deposits = listOf(
            DepositRule(IgboPackBlocks.stormCrystal.id, minZ = 6, maxZ = 20, chance = 0.07f, clusterSize = 2),
            ironVein,
        ),
        ambientLight = 8,
    )

    val bronzeCatacombs = BiomeDefinition(
        id = "$NS:bronze_catacombs",
        name = "Lost Catacombs of Igbo-Ukwu",
        description = "Subterranean masonry holding roped bronze vessels and old torchlight.",
        surfaceBlockId = IgboPackBlocks.catacombMasonry.id,
        subsurfaceBlockId = IgboPackBlocks.catacombMasonry.id,
        bedrockFillerBlockId = IgboPackBlocks.graniteStone.id,
        heightBias = -3,
        roughness = 0.5f,
        scatter = listOf(
            ScatterRule(IgboPackBlocks.bronzeBrazier.id, chance = 0.02f, height = 1),
        ),
        deposits = listOf(bronzeVein.copy(chance = 0.22f, clusterSize = 5), ironVein),
        ambientLight = 5,
    )

    val mistMarsh = BiomeDefinition(
        id = "$NS:mist_marsh",
        name = "Benue River Mist Marsh",
        description = "Weathered boardwalks over murky spirit water and glowing reeds.",
        surfaceBlockId = IgboPackBlocks.riverClay.id,
        subsurfaceBlockId = IgboPackBlocks.riverClay.id,
        bedrockFillerBlockId = IgboPackBlocks.graniteStone.id,
        heightBias = -2,
        roughness = 0.2f,
        scatter = listOf(
            ScatterRule(IgboPackBlocks.palmReed.id, chance = 0.05f, height = 1),
            ScatterRule(IgboPackBlocks.irokoCanopy.id, chance = 0.02f, height = 1),
        ),
        deposits = listOf(ironVein.copy(chance = 0.18f)),
        ambientLight = 9,
    )

    val all = listOf(sacredGrove, ozoCourtyard, thunderPeak, bronzeCatacombs, mistMarsh)
}
