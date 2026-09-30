package com.stratum.engine.worldgen

import com.stratum.core.domain.world.TerrainRecipe
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps generation cheap enough for a phone.
 *
 * The guard is work, not time: noise samples per chunk are what a chunk
 * costs, and a count cannot be flaky. The budgets sit about twice over what
 * the presets take today, so an ordinary change passes and a pass that starts
 * sampling per block instead of per column does not. A time bound is kept
 * too, generous enough for a loaded build machine, to catch anything the
 * count cannot see.
 */
class BudgetTest {

    private fun samplesPerChunk(recipe: TerrainRecipe): Long {
        val counter = SampleCounter()
        val generator = Fixtures.generator(Fixtures.context(recipe), counter)
        val area = Fixtures.grid(0, 3)
        area.forEach { generator.generate(it, Fixtures.registry) }
        return counter.samples / area.size
    }

    @Test
    fun `each preset stays inside its noise budget per chunk`() {
        val budgets = mapOf(
            TerrainRecipe.OVERWORLD to 12_000L,
            TerrainRecipe.ISLANDS to 14_000L,
            TerrainRecipe.CAVERNS to 16_000L,
            TerrainRecipe.FLAT_GENERATOR to 6_000L,
        )
        budgets.forEach { (id, budget) ->
            val used = samplesPerChunk(Fixtures.recipe.copy(generatorId = id))
            assertTrue(used in 1..budget, "$id took $used noise samples per chunk, over its budget of $budget")
        }
    }

    @Test
    fun `an overworld chunk generates in well under a frame budget`() {
        val generator = Fixtures.generator()
        // Warm the JIT and the structure caches first.
        Fixtures.grid(-2, 1).forEach { generator.generate(it, Fixtures.registry) }
        val area = Fixtures.grid(10, 17)
        val start = System.nanoTime()
        area.forEach { generator.generate(it, Fixtures.registry) }
        val perChunkMs = (System.nanoTime() - start) / 1e6 / area.size
        assertTrue(perChunkMs < 60.0, "an overworld chunk took $perChunkMs ms")
    }
}
