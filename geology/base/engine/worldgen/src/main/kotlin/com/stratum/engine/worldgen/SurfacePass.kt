package com.stratum.engine.worldgen

import com.stratum.core.domain.world.Chunk

/**
 * Fills each column with its material: bedrock at the floor, then filler,
 * the recipe's strata or the biome's subsurface, and the biome's surface on
 * top. An underground biome, where the climate puts one, takes over the
 * filler in its band of depth.
 *
 * The same layering the layered generator writes, so a pack moving to a
 * pipeline preset keeps the ground it had.
 */
internal object SurfacePass : PassFactory {

    private const val SUBSURFACE_DEPTH = 3

    override fun create(setup: PassSetup): WorldgenPass {
        val strata = setup.recipe.strata
        val biomes = setup.services.biomes
        return WorldgenPass { ctx ->
            ctx.ensureSurface()
            val blocks = ctx.blocks
            val strataIndices = strata.map { blocks.orNull(it.blockId) }
            val climate = ClimateSample()
            for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
                val surfaceZ = ctx.surfaceAt(lx, ly)
                val biome = ctx.biomeAt(lx, ly)
                val filler = blocks.of(biome.bedrockFillerBlockId)
                val subsurface = blocks.of(biome.subsurfaceBlockId)
                val chunk = ctx.chunk
                chunk.setBlock(lx, ly, 0, blocks.bedrock)
                for (z in 1 until surfaceZ) {
                    val index = if (strata.isEmpty() && z >= surfaceZ - SUBSURFACE_DEPTH) subsurface else filler
                    chunk.setBlock(lx, ly, z, index)
                }
                // Recipe strata run top down from just under the surface; any
                // depth they do not reach stays filler.
                var z = surfaceZ - 1
                strata.forEachIndexed { i, stratum ->
                    val index = strataIndices[i] ?: return@forEachIndexed
                    repeat(stratum.thickness) {
                        if (z >= 1) chunk.setBlock(lx, ly, z--, index)
                    }
                }
                if (biomes.hasUnderground) {
                    ctx.climateAt(lx, ly, climate)
                    for (depthZ in 1 until surfaceZ) {
                        val below = biomes.undergroundAt(climate, surfaceZ - depthZ) ?: continue
                        chunk.setBlock(lx, ly, depthZ, blocks.of(below.bedrockFillerBlockId))
                    }
                }
                chunk.setBlock(lx, ly, surfaceZ, blocks.of(biome.surfaceBlockId))
            }
        }
    }
}
