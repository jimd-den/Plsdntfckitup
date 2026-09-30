package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeComposition
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.DepositRule
import com.stratum.core.domain.content.Landmark
import com.stratum.core.domain.content.ScatterRule
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.CarverRule
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.ClimatePoint
import com.stratum.core.domain.world.ClimateSpec
import com.stratum.core.domain.world.DungeonLayout
import com.stratum.core.domain.world.LiquidRule
import com.stratum.core.domain.world.OreRule
import com.stratum.core.domain.world.PieceConnector
import com.stratum.core.domain.world.PieceSide
import com.stratum.core.domain.world.StructurePiece
import com.stratum.core.domain.world.StructurePlacement
import com.stratum.core.domain.world.StructureTemplate
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.TreeRule
import com.stratum.core.domain.world.WorldConfig

/** A small world with one block per job, so tests can tell every feature apart. */
object Fixtures {
    val grass = BlockType("t:grass", "Grass", BlockMaterial.SOIL)
    val dirt = BlockType("t:dirt", "Dirt", BlockMaterial.SOIL)
    val stone = BlockType("t:stone", "Stone")
    val sand = BlockType("t:sand", "Sand")
    val ore = BlockType("t:ore", "Ore", BlockMaterial.ORE)
    val vein = BlockType("t:vein", "Vein", BlockMaterial.ORE)
    val log = BlockType("t:log", "Log", BlockMaterial.WOOD)
    val leaves = BlockType("t:leaves", "Leaves", BlockMaterial.FOLIAGE, isOpaque = false)
    val water = BlockType("t:water", "Water", BlockMaterial.LIQUID, isSolid = false, isOpaque = false)
    val brick = BlockType("t:brick", "Brick")
    val tile = BlockType("t:tile", "Tile")
    val torch = BlockType("t:torch", "Torch", isSolid = false, isOpaque = false, glyph = "*")
    val path = BlockType("t:path", "Path", BlockMaterial.SOIL)
    val shrine = BlockType("t:shrine", "Shrine", glyph = "S")
    val glowstone = BlockType("t:glow", "Glow")

    val registry: BlockRegistry = BlockRegistry.build(
        listOf(grass, dirt, stone, sand, ore, vein, log, leaves, water, brick, tile, torch, path, shrine, glowstone),
    )

    val meadow = BiomeDefinition(
        id = "t:meadow", name = "Meadow", surfaceBlockId = grass.id, subsurfaceBlockId = dirt.id, bedrockFillerBlockId = stone.id,
        heightBias = 0, roughness = 0.8f,
        scatter = listOf(ScatterRule(torch.id, chance = 0.02f)),
        deposits = listOf(DepositRule(ore.id, minZ = 2, maxZ = 8, chance = 0.2f, clusterSize = 3)),
        composition = BiomeComposition(pathBlockId = path.id, landmark = Landmark(centreBlockId = shrine.id, ringBlockId = torch.id)),
        temperature = 0.6f,
    )

    val highlands = BiomeDefinition(
        id = "t:highlands", name = "Highlands", surfaceBlockId = stone.id, subsurfaceBlockId = stone.id, bedrockFillerBlockId = stone.id,
        heightBias = 6, roughness = 1.4f, temperature = 0.2f,
    )

    val dunes = BiomeDefinition(
        id = "t:dunes", name = "Dunes", surfaceBlockId = sand.id, subsurfaceBlockId = sand.id, bedrockFillerBlockId = stone.id,
        heightBias = -2, roughness = 0.4f, temperature = 0.9f,
    )

    /** An underground biome, found only in its band of depth. */
    val glowCaves = BiomeDefinition(
        id = "t:glow_caves", name = "Glow Caves", surfaceBlockId = glowstone.id, subsurfaceBlockId = glowstone.id,
        bedrockFillerBlockId = glowstone.id,
    )

    val biomes = listOf(meadow, highlands, dunes)

    val crypt = StructureTemplate(
        id = "t:crypt", name = "Crypt",
        placement = StructurePlacement(spacing = 64, chance = 1f),
        dungeon = DungeonLayout(
            floorBlockId = tile.id, wallBlockId = brick.id, lightBlockId = torch.id, depth = 8, extent = 20,
            minRooms = 3, maxRooms = 5, enemyIds = listOf("t:ghoul"), bossId = "t:lich", lootRef = "t:crypt_loot",
        ),
    )

    private val courtyard = StructurePiece(
        id = "t:court", start = true,
        palette = mapOf('#' to brick.id, '_' to tile.id, 'L' to "@loot"),
        layers = listOf(
            listOf("#####", "#___#", "#___#", "#___#", "#####"),
            listOf("#   #", "     ", "  L  ", "     ", "#   #"),
        ),
        connectors = listOf(PieceConnector(PieceSide.EAST, 2), PieceConnector(PieceSide.WEST, 2)),
    )

    private val wing = StructurePiece(
        id = "t:wing",
        palette = mapOf('#' to brick.id, '_' to tile.id, 'E' to "@enemy:t:ghoul"),
        layers = listOf(listOf("###", "#_#", "###"), listOf("   ", " E ", "   ")),
        connectors = listOf(PieceConnector(PieceSide.EAST, 1), PieceConnector(PieceSide.WEST, 1)),
    )

    val ruin = StructureTemplate(
        id = "t:ruin", name = "Ruin",
        placement = StructurePlacement(spacing = 48, chance = 0.8f),
        pieces = listOf(courtyard, wing), maxPieces = 3, foundationBlockId = stone.id,
    )

    val recipe = TerrainRecipe(
        generatorId = TerrainRecipe.OVERWORLD,
        climate = ClimateSpec(
            points = listOf(
                ClimatePoint(meadow.id, 0.5f, 0.6f),
                ClimatePoint(highlands.id, 0.15f, 0.3f),
                ClimatePoint(dunes.id, 0.9f, 0.1f),
            ),
        ),
        ores = listOf(OreRule(vein.id, minZ = 2, maxZ = 12, veinsPerChunk = 3f, veinSize = 10)),
        trees = listOf(TreeRule(log.id, leaves.id, chance = 0.03f, canopyRadius = 2, biomeIds = listOf(meadow.id))),
    )

    val config = WorldConfig(seed = 20260927L, seaLevel = 14, surfaceVariation = 6)

    fun context(
        recipe: TerrainRecipe = this.recipe,
        config: WorldConfig = this.config,
        biomes: List<BiomeDefinition> = this.biomes,
        structures: List<StructureTemplate> = listOf(crypt, ruin),
    ) = TerrainContext(config, biomes, recipe, structures = structures)

    fun generator(context: TerrainContext = context(), counter: SampleCounter? = null): PipelineTerrainGenerator {
        val specs = context.recipe.passes.ifEmpty { StratumWorldgen.presets.getValue(context.recipe.generatorId) }
        return PipelineTerrainGenerator.build(context, specs, StratumWorldgen.passes, counter)
    }

    val sea = LiquidRule(water.id, maxZ = 14)

    val deepCarvers = listOf(CarverRule("caverns", minZ = 2, maxZ = 20, amount = 0.5f, size = 5f, headroom = 3))

    fun Chunk.count(index: Int): Int {
        var n = 0
        for (z in 0 until Chunk.HEIGHT) for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) if (blockAt(x, y, z) == index) n++
        return n
    }

    fun grid(from: Int, to: Int): List<ChunkPos> = (from..to).flatMap { y -> (from..to).map { x -> ChunkPos(x, y) } }
}
