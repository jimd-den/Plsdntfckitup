package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.LiquidTarget

/**
 * Seas, lakes and aquifers: air up to a level, filled with the rule's liquid.
 *
 * Open air above the ground fills wherever the ground is below the level,
 * which is a sea for an archipelago and a lake in a hollow. Cave air fills
 * by region: a coarse cell is either an aquifer or dry, chosen from the seed
 * and the cell, so a flooded cave system is flooded end to end and never
 * depends on which chunk was made first.
 */
internal object LiquidsPass : PassFactory {

    private const val AQUIFER_CELL = 48
    private const val AQUIFER_SALT = 88_001

    override fun create(setup: PassSetup): WorldgenPass {
        val rules = setup.recipe.liquids
        val seed = setup.services.seed
        return WorldgenPass { chunk ->
            if (rules.isEmpty()) return@WorldgenPass
            chunk.ensureSurface()
            rules.forEachIndexed { slot, rule ->
                val liquid = chunk.blocks.of(rule.blockId)
                val maxZ = rule.maxZ.coerceAtMost(Chunk.HEIGHT - 1)
                for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
                    val surface = chunk.surfaceAt(lx, ly)
                    val wx = chunk.originX + lx
                    val wy = chunk.originY + ly
                    val open = rule.target != LiquidTarget.CAVES
                    val caves = rule.target != LiquidTarget.OPEN && flooded(seed, wx, wy, slot, rule.share)
                    for (z in rule.minZ.coerceAtLeast(1)..maxZ) {
                        val above = z > surface
                        if ((above && !open) || (!above && !caves)) continue
                        if (chunk.chunk.blockAt(lx, ly, z) == BlockRegistry.AIR_INDEX) chunk.chunk.setBlock(lx, ly, z, liquid)
                    }
                }
            }
        }
    }

    private fun flooded(seed: Long, x: Int, y: Int, slot: Int, share: Float): Boolean {
        if (share >= 1f) return true
        if (share <= 0f) return false
        val cx = Math.floorDiv(x, AQUIFER_CELL)
        val cy = Math.floorDiv(y, AQUIFER_CELL)
        return PositionalRandom.floatAt(seed, cx, cy, AQUIFER_SALT + slot) < share
    }
}
