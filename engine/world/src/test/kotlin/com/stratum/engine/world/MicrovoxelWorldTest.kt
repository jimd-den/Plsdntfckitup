package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import com.stratum.engine.microvoxel.gen.CityPlanStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A microvoxel world plays as an ordinary block world with the built-in pack. */
class MicrovoxelWorldTest {

    private val igbo = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))

    private fun content(recipe: TerrainRecipe = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)) = igbo.copy(terrain = recipe)

    private fun session(seed: Long = 20260928L, recipe: TerrainRecipe = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)) =
        WorldSession(content = content(recipe), config = WorldConfig(seed = seed, simulationRadius = 1))

    @Test
    fun `the hero spawns on the ground and can walk`() {
        val s = session()
        val feet = s.player.blockPos
        assertTrue(s.world.blockAt(feet).isAir, "spawned inside a block")
        assertTrue(s.world.isSolid(feet.below()), "nothing underfoot")
        val outcomes = listOf(s.move(1f, 0f), s.move(0f, 1f), s.move(-1f, 0f), s.move(0f, -1f))
        assertTrue(outcomes.any { it.moved }, "the hero could not move in any direction")
    }

    @Test
    fun `a minute of play runs without trouble`() {
        val s = session()
        repeat(600) { i ->
            s.setMoveInput(if (i / 60 % 2 == 0) 1f else -1f, 0.3f)
            s.tick(0.1f)
        }
        assertTrue(s.player.isAlive || s.player.maxHealth > 0)
        assertNotNull(s.currentBiome)
    }

    @Test
    fun `the session exposes the microvoxels behind its blocks`() {
        val s = session()
        val micro = assertNotNull(s.microTerrain)
        // Away from the starting town (the pack's towns are stamped over the land), the blocks are what the microvoxels made.
        val far = listOf(ChunkPos(20, 20), ChunkPos(-24, 9), ChunkPos(7, -30), ChunkPos(-15, -18)).map { s.editableWorld.loadChunk(it) }
        var same = 0; var total = 0
        for (chunk in far) for (x in 0 until 16) for (y in 0 until 16) {
            val wx = chunk.pos.originX + x; val wy = chunk.pos.originY + y
            val z = s.world.surfaceAt(wx, wy)
            total++
            if (s.world.blockIndexAt(BlockPos(wx, wy, z)) == micro.generatedBlock(wx, wy, z)) same++
        }
        assertTrue(same > total * 3 / 4, "only $same of $total surface blocks match the microvoxels")
    }

    @Test
    fun `the land fits the block column and uses the pack's own blocks`() {
        val s = session()
        val used = HashSet<String>()
        for (chunk in s.world.loadedChunks) for (x in 0 until 16) for (y in 0 until 16) {
            val top = s.world.surfaceAt(chunk.pos.originX + x, chunk.pos.originY + y)
            assertTrue(top in 1 until 47, "surface at $top")
            used += s.world.blockAt(BlockPos(chunk.pos.originX + x, chunk.pos.originY + y, top)).id
        }
        assertTrue(used.all { it.startsWith("igbo:") || it.startsWith("stratum:") }, "foreign blocks: $used")
        assertTrue(used.size >= 2, "a single-block landscape: $used")
    }

    @Test
    fun `monsters wait in the wild and towns are marked`() {
        val context = content().terrainContext(WorldConfig(seed = 20260928L))
        val gen = MicrovoxelTerrainGenerator(context)
        gen.paletteFor(igbo.registry)
        val markers = (-12..12).flatMap { y -> (-12..12).flatMap { x -> gen.markersIn(ChunkPos(x, y)) } }
        assertTrue(markers.count { it.kind == WorldMarkerKind.ENEMY_SPAWN } > 50, "too few spawn markers")
        val city = gen.micro.fields.require(CityPlanStage.KEY)
        for (m in markers.filter { it.kind == WorldMarkerKind.ENEMY_SPAWN })
            assertTrue(!city.isOccupied(m.x * 4, m.y * 4), "a monster was placed on a road or in a house at $m")
    }

    @Test
    fun `an edit shows as a difference from what was generated`() {
        val s = session()
        val micro = assertNotNull(s.microTerrain)
        val chunk = s.world.loadedChunks.first()
        val p = (0 until 16).flatMap { x -> (0 until 16).map { y -> chunk.pos.originX + x to chunk.pos.originY + y } }
            .map { (x, y) -> BlockPos(x, y, s.world.surfaceAt(x, y)) }
            .first { s.world.blockIndexAt(it) != 0 && s.world.blockIndexAt(it) == micro.generatedBlock(it.x, it.y, it.z) }
        s.editableWorld.setBlock(p, BlockRegistry.AIR_INDEX)
        assertNotEquals(s.world.blockIndexAt(p), micro.generatedBlock(p.x, p.y, p.z))
    }

    @Test
    fun `a recipe written as data configures the stages`() {
        val recipe = TerrainRecipe(
            generatorId = TerrainRecipe.MICROVOXEL,
            options = mapOf("spawns" to "0.1"),
            passes = listOf(
                PassSpec("micro:terrain", mapOf("height" to "0.1")),
                PassSpec("micro:groundcover"),
            ),
        )
        val s = session(recipe = recipe)
        assertTrue(s.world.isSolid(s.player.blockPos.below()))
        // An unknown stage fails loudly, naming what exists.
        val bad = recipe.copy(passes = listOf(PassSpec("micro:nope")))
        val error = assertFailsWith<IllegalArgumentException> { session(recipe = bad) }
        assertTrue("micro:terrain" in error.message.orEmpty())
    }
}
