package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.OreRule
import kotlin.random.Random

/**
 * Ores: each biome's own deposits, then the recipe's veins.
 *
 * Deposits are the layered generator's per-column blobs, kept so every pack's
 * biomes still hold what they always held. Veins are the recipe's: a short
 * random walk from a start seeded by the chunk it begins in. A vein can wander
 * across a border, so each chunk replays the veins of its eight neighbours as
 * well as its own and keeps the cells that land on it -- [OreRule.veinSize] is
 * capped so no vein can reach farther than that.
 *
 * Option: `biomeDeposits` (true) turns the biome deposits off, for a pack that
 * wants only its own veins.
 */
internal object OresPass : PassFactory {

    private const val DEPOSIT_SALT = 1000
    private const val VEIN_SALT = 4_401L

    override fun create(setup: PassSetup): WorldgenPass {
        val services = setup.services
        val seed = services.seed
        val richness = setup.config.oreRichness
        val deposits = setup.options.boolean("biomeDeposits", true)
        val veins = setup.recipe.ores
        return WorldgenPass { chunk ->
            chunk.ensureSurface()
            if (deposits) biomeDeposits(chunk, seed, richness)
            if (veins.isNotEmpty()) {
                val replaceable = veins.map { rule -> rule.replaces.mapNotNull(chunk.blocks::orNull).toSet() }
                for (cy in chunk.pos.y - 1..chunk.pos.y + 1) for (cx in chunk.pos.x - 1..chunk.pos.x + 1) {
                    veins.forEachIndexed { slot, rule -> vein(chunk, services, ChunkPos(cx, cy), slot, rule, replaceable[slot], richness) }
                }
            }
        }
    }

    private fun biomeDeposits(chunk: ChunkContext, seed: Long, richness: Float) {
        for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
            val worldX = chunk.originX + lx
            val worldY = chunk.originY + ly
            val surfaceZ = chunk.surfaceAt(lx, ly)
            chunk.biomeAt(lx, ly).deposits.forEachIndexed { ruleIndex, rule ->
                val top = minOf(rule.maxZ, surfaceZ - 1)
                if (rule.minZ > top) return@forEachIndexed
                val roll = PositionalRandom.floatAt(seed, worldX, worldY, DEPOSIT_SALT + ruleIndex)
                if (roll >= rule.chance * richness) return@forEachIndexed
                // The roll that chose this column also picks where in the band the blob sits.
                val anchor = rule.minZ + ((roll / rule.chance.coerceAtLeast(1e-6f)) * (top - rule.minZ + 1)).toInt()
                val index = chunk.blocks.of(rule.blockId)
                val half = (rule.clusterSize / 2).coerceAtLeast(0)
                for (z in (anchor - half)..(anchor + half)) {
                    if (z <= 0 || z > top) continue
                    if (chunk.blocks.isRock(chunk.chunk.blockAt(lx, ly, z))) chunk.chunk.setBlock(lx, ly, z, index)
                }
            }
        }
    }

    private fun vein(chunk: ChunkContext, services: WorldServices, home: ChunkPos, slot: Int, rule: OreRule, replaces: Set<Int>, richness: Float) {
        val random = Random(services.seed * 0x2545F491L + VEIN_SALT * (slot + 1) + home.x * 341_873_128_712L + home.y * 132_897_987_541L)
        val expected = rule.veinsPerChunk * richness
        val count = expected.toInt() + if (random.nextFloat() < expected - expected.toInt()) 1 else 0
        val ore = chunk.blocks.of(rule.blockId)
        repeat(count) {
            var x = home.originX + random.nextInt(Chunk.SIZE)
            var y = home.originY + random.nextInt(Chunk.SIZE)
            var z = rule.minZ + random.nextInt(rule.maxZ - rule.minZ + 1)
            // Rolled whether or not it is used, so one vein's biome never shifts the next vein's dice.
            val steps = IntArray(rule.veinSize) { random.nextInt(6) }
            if (rule.biomeIds.isNotEmpty() && services.biomes.biomeAt(x, y).id !in rule.biomeIds) return@repeat
            for (step in steps) {
                if (chunk.containsWorld(x, y) && z in rule.minZ..rule.maxZ) {
                    val existing = chunk.get(x, y, z)
                    val allowed = if (replaces.isEmpty()) chunk.blocks.isRock(existing) else existing in replaces
                    if (allowed && existing != BlockRegistry.AIR_INDEX) chunk.set(x, y, z, ore)
                }
                when (step) {
                    0 -> x++
                    1 -> x--
                    2 -> y++
                    3 -> y--
                    4 -> z++
                    else -> z--
                }
            }
        }
    }
}
