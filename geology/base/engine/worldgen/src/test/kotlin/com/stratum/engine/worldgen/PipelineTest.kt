package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.ClimatePoint
import com.stratum.core.domain.world.ClimateSpec
import com.stratum.core.domain.world.LiquidRule
import com.stratum.core.domain.world.LiquidTarget
import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TerrainGeneratorRegistry
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.engine.worldgen.Fixtures.count
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PipelineTest {

    private val registry = Fixtures.registry
    private val air = BlockRegistry.AIR_INDEX

    private fun underground(generator: PipelineTerrainGenerator, positions: List<ChunkPos>): Pair<Int, Int> {
        var open = 0
        var total = 0
        positions.forEach { pos ->
            val chunk = generator.generate(pos, registry)
            for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
                val top = chunk.surfaceAt(x, y)
                for (z in 1 until top - 4) {
                    total++
                    if (chunk.blockAt(x, y, z) == air) open++
                }
            }
        }
        return open to total
    }

    @Test
    fun `every preset is registered and builds a floored world`() {
        val generators = StratumWorldgen.registerPresets(TerrainGeneratorRegistry())
        listOf(TerrainRecipe.OVERWORLD, TerrainRecipe.ISLANDS, TerrainRecipe.CAVERNS, TerrainRecipe.UNDERWORLD, TerrainRecipe.FLAT_GENERATOR).forEach { id ->
            val generator = generators.create(Fixtures.context(Fixtures.recipe.copy(generatorId = id)))
            val chunk = generator.generate(ChunkPos(2, -1), registry)
            for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
                assertEquals(registry.indexOf(BlockType.BEDROCK.id), chunk.blockAt(x, y, 0), "$id left a hole in bedrock")
                assertTrue(chunk.surfaceAt(x, y) in 1 until Chunk.HEIGHT, "$id has an empty column")
            }
        }
    }

    @Test
    fun `the caverns preset is far more hollow than the overworld`() {
        val positions = Fixtures.grid(0, 2)
        val (overOpen, overTotal) = underground(Fixtures.generator(), positions)
        val (deepOpen, deepTotal) = underground(Fixtures.generator(Fixtures.context(Fixtures.recipe.copy(generatorId = TerrainRecipe.CAVERNS))), positions)
        val over = overOpen.toFloat() / overTotal
        val deep = deepOpen.toFloat() / deepTotal
        assertTrue(over in 0.01f..0.3f, "the overworld should have some caves, not be hollow: $over")
        assertTrue(deep > over * 1.5f, "caverns ($deep) should be more open than the overworld ($over)")
    }

    @Test
    fun `islands rise out of a sea the pack fills`() {
        val recipe = Fixtures.recipe.copy(generatorId = TerrainRecipe.ISLANDS, liquids = listOf(Fixtures.sea))
        val generator = Fixtures.generator(Fixtures.context(recipe))
        val water = registry.indexOf(Fixtures.water.id)
        var wet = 0
        var land = 0
        Fixtures.grid(-6, 5).forEach { pos ->
            val chunk = generator.generate(pos, registry)
            for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
                if (chunk.blockAt(x, y, Fixtures.sea.maxZ) == water) wet++ else land++
            }
        }
        val share = land.toFloat() / (land + wet)
        assertTrue(share in 0.1f..0.9f, "an archipelago is land and sea, got $share land")
    }

    @Test
    fun `the flat preset is level`() {
        val generator = Fixtures.generator(Fixtures.context(Fixtures.recipe.copy(generatorId = TerrainRecipe.FLAT_GENERATOR, trees = emptyList()), structures = emptyList()))
        val chunk = generator.generate(ChunkPos(3, 3), registry)
        for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val top = (Chunk.HEIGHT - 1 downTo 0).first { chunk.blockAt(x, y, it) != air && registry.typeOf(chunk.blockAt(x, y, it)).isSolid }
            assertEquals(Fixtures.config.seaLevel, top)
        }
    }

    @Test
    fun `a pack can reorder, drop and add passes, including one registered in code`() {
        val passes = StratumWorldgen.passes.copy().register("test:pillar", PassFactory { setup ->
            val height = setup.options.int("height", 3)
            WorldgenPass { chunk ->
                val z = chunk.surfaceAt(0, 0)
                for (dz in 1..height) chunk.chunk.setBlock(0, 0, z + dz, chunk.blocks.of(Fixtures.brick.id))
            }
        })
        val specs = listOf(
            PassSpec(StratumWorldgen.CLIMATE), PassSpec(StratumWorldgen.FLAT_SHAPE, mapOf("level" to "10")),
            PassSpec(StratumWorldgen.SURFACE), PassSpec("test:pillar", mapOf("height" to "5")),
        )
        val generator = PipelineTerrainGenerator.build(Fixtures.context(), specs, passes)
        val chunk = generator.generate(ChunkPos(0, 0), registry)
        assertEquals(listOf("stratum:climate", "stratum:flat_shape", "stratum:surface", "test:pillar"), generator.passIds)
        assertEquals(15, chunk.surfaceAt(0, 0))
        assertEquals(10, chunk.surfaceAt(1, 1))
        // Carvers were dropped, so nothing under the ground is open.
        assertEquals(0, (1 until 10).count { chunk.blockAt(5, 5, it) == air })
    }

    @Test
    fun `an unknown pass or carver is an error naming what exists`() {
        val badPass = assertFailsWith<IllegalArgumentException> {
            Fixtures.generator(Fixtures.context(Fixtures.recipe.copy(passes = listOf(PassSpec("nope:pass")))))
        }
        assertTrue("stratum:hills" in badPass.message.orEmpty())
        val badCarver = assertFailsWith<IllegalArgumentException> {
            Fixtures.generator(Fixtures.context(Fixtures.recipe.copy(carvers = listOf(com.stratum.core.domain.world.CarverRule("lava_tubes")))))
        }
        assertTrue("tunnels" in badCarver.message.orEmpty())
        assertFailsWith<IllegalArgumentException> {
            Fixtures.generator(Fixtures.context(Fixtures.recipe.copy(passes = listOf(PassSpec(StratumWorldgen.HILLS, mapOf("base" to "high"))))))
        }
    }

    @Test
    fun `biomes follow the climate and their borders are slopes, not walls`() {
        val generator = Fixtures.generator()
        val services = WorldServices(Fixtures.context())
        val map = ClimateMap(services, Fixtures.recipe.climate)
        val sample = ClimateSample()
        val seen = HashSet<String>()
        var worst = 0
        for (y in -400 until 400 step 2) for (x in -400 until 400 step 2) {
            map.sample(x, y, sample)
            seen += sample.biome.id
            val nearest = Fixtures.recipe.climate!!.points.minBy {
                (it.temperature - sample.temperature).let { d -> d * d } + (it.moisture - sample.moisture).let { d -> d * d }
            }
            assertEquals(nearest.biomeId, sample.biome.id)
        }
        for (y in -200 until 200) for (x in -200 until 200) {
            worst = maxOf(worst, abs(generator.groundAt(x, y) - generator.groundAt(x + 1, y)), abs(generator.groundAt(x, y) - generator.groundAt(x, y + 1)))
        }
        assertEquals(Fixtures.biomes.map { it.id }.toSet(), seen, "every biome appears")
        assertTrue(worst <= 3, "a border between biomes six blocks apart became a $worst-block wall")
    }

    @Test
    fun `an underground biome fills its band of depth`() {
        val recipe = Fixtures.recipe.copy(
            climate = ClimateSpec(Fixtures.recipe.climate!!.points + ClimatePoint(Fixtures.glowCaves.id, 0.5f, 0.5f, minDepth = 6, maxDepth = 9)),
            carvers = emptyList(), passes = listOf(PassSpec(StratumWorldgen.CLIMATE), PassSpec(StratumWorldgen.HILLS), PassSpec(StratumWorldgen.SURFACE)),
        )
        val generator = Fixtures.generator(Fixtures.context(recipe, biomes = Fixtures.biomes + Fixtures.glowCaves))
        val chunk = generator.generate(ChunkPos(0, 0), registry)
        val glow = registry.indexOf(Fixtures.glowstone.id)
        val top = chunk.surfaceAt(4, 4)
        assertEquals(glow, chunk.blockAt(4, 4, top - 7))
        assertTrue(chunk.blockAt(4, 4, top - 2) != glow)
        assertTrue(generator.biomeAt(4, 4).id != Fixtures.glowCaves.id, "an underground biome is never the surface's")
    }

    @Test
    fun `cave aquifers flood whole regions and open seas stay above ground`() {
        val recipe = Fixtures.recipe.copy(liquids = listOf(LiquidRule(Fixtures.water.id, maxZ = 10, target = LiquidTarget.CAVES, share = 1f)))
        val generator = Fixtures.generator(Fixtures.context(recipe, structures = emptyList()))
        val water = registry.indexOf(Fixtures.water.id)
        var flooded = 0
        Fixtures.grid(0, 1).forEach { pos ->
            val chunk = generator.generate(pos, registry)
            flooded += chunk.count(water)
            for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
                val surface = generator.groundAt(pos.originX + x, pos.originY + y)
                for (z in 1..minOf(10, surface - 1)) assertTrue(chunk.blockAt(x, y, z) != air, "cave air at ($x,$y,$z) was left dry under a full aquifer")
                for (z in surface + 1 until Chunk.HEIGHT) assertTrue(chunk.blockAt(x, y, z) != water, "a cave aquifer spilled above ground")
            }
        }
        assertTrue(flooded > 0, "the aquifer filled nothing")
    }

    @Test
    fun `ore veins stay in their band`() {
        val chunks = Fixtures.grid(0, 2).map { Fixtures.generator().generate(it, registry) }
        val vein = registry.indexOf(Fixtures.vein.id)
        var found = 0
        chunks.forEach { chunk ->
            for (z in 0 until Chunk.HEIGHT) for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
                if (chunk.blockAt(x, y, z) == vein) {
                    found++
                    assertTrue(z in 2..12, "a vein reached z=$z")
                }
            }
        }
        assertTrue(found > 20, "veins were placed: $found")
    }
}
