package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.TreeRule

/**
 * Trees with canopies that spill over their neighbours and across chunk
 * borders.
 *
 * Whether a column roots a tree is a roll seeded by the column, so a chunk
 * can ask about every root within a canopy's reach of its edge -- including
 * roots in chunks nobody has generated -- and draw the leaves that fall on
 * it. The cheap roll comes first; only a root that passes it pays for a
 * biome and a height.
 */
internal object TreesPass : PassFactory {

    private const val TREE_SALT = 6_100

    override fun create(setup: PassSetup): WorldgenPass {
        val rules = setup.recipe.trees
        val services = setup.services
        val seed = services.seed
        // Nothing roots under open water.
        val flooded = setup.recipe.liquids.filter { it.target != com.stratum.core.domain.world.LiquidTarget.CAVES }.maxOfOrNull { it.maxZ } ?: 0
        return WorldgenPass { chunk ->
            if (rules.isEmpty()) return@WorldgenPass
            chunk.ensureSurface()
            val composition = services.composition
            val climate = ClimateSample()
            rules.forEachIndexed { slot, rule ->
                val trunk = chunk.blocks.of(rule.trunkBlockId)
                val leaves = chunk.blocks.of(rule.leafBlockId)
                val r = rule.canopyRadius
                for (wy in chunk.originY - r until chunk.originY + Chunk.SIZE + r) {
                    for (wx in chunk.originX - r until chunk.originX + Chunk.SIZE + r) {
                        val roll = PositionalRandom.floatAt(seed, wx, wy, TREE_SALT + slot)
                        // Rules are rolled against their chance twice at most: here,
                        // and against the grove density below. Most columns stop here.
                        if (roll >= rule.chance * GROVE_CEILING) continue
                        val inside = chunk.containsWorld(wx, wy)
                        val sample = if (inside) chunk.climateAt(wx - chunk.originX, wy - chunk.originY, climate)
                        else services.biomes.sample(wx, wy, climate)
                        val biome = sample.biome
                        if (rule.biomeIds.isNotEmpty() && biome.id !in rule.biomeIds) continue
                        val density = composition?.density(wx, wy) ?: 1f
                        if (roll >= rule.chance * density) continue
                        if (composition?.isClear(wx, wy, biome) == true) continue
                        // The height field, never this chunk's own columns: the
                        // chunk next door sees only the field, and both must
                        // agree where the tree stands.
                        val ground = services.height.heightAt(wx, wy, sample)
                        if (ground + 1 <= flooded) continue
                        grow(chunk, rule, wx, wy, ground, trunk, leaves, seed, slot)
                    }
                }
            }
        }
    }

    /** The largest multiple of a rule's chance the grove density can reach. */
    private const val GROVE_CEILING = 2f

    private fun grow(chunk: ChunkContext, rule: TreeRule, x: Int, y: Int, ground: Int, trunk: Int, leaves: Int, seed: Long, slot: Int) {
        val span = rule.maxHeight - rule.minHeight + 1
        val height = rule.minHeight + PositionalRandom.intAt(seed, x, y, TREE_SALT + 50 + slot, span)
        val top = ground + height
        if (top + 1 >= Chunk.HEIGHT) return
        for (z in ground + 1..top) chunk.set(x, y, z, trunk)
        val r = rule.canopyRadius
        for (dz in -1..1) for (dy in -r..r) for (dx in -r..r) {
            // A rounded canopy, narrower at its top and bottom.
            val reach = if (dz == 0) r else r - 1
            if (dx * dx + dy * dy > reach * reach + reach) continue
            val z = top + dz + 1
            if (dx == 0 && dy == 0 && dz < 0) continue
            if (z >= Chunk.HEIGHT || !chunk.containsWorld(x + dx, y + dy)) continue
            if (chunk.get(x + dx, y + dy, z) == BlockRegistry.AIR_INDEX) chunk.set(x + dx, y + dy, z, leaves)
        }
    }
}
