package com.stratum.engine.worldgen

import com.stratum.core.domain.world.CarverRule
import com.stratum.core.domain.world.Chunk

/**
 * Runs the recipe's carvers in order, or the preset's own when the recipe
 * names none.
 *
 * Option: `defaults` picks the preset carvers -- `overworld` (caves and
 * tunnels under hills), `caverns` (stacked cavern layers joined by tunnels)
 * or `none`.
 */
internal class CarversPass(private val registry: CarverRegistry) : PassFactory {

    override fun create(setup: PassSetup): WorldgenPass {
        val rules = setup.recipe.carvers.ifEmpty {
            when (val defaults = setup.options.string("defaults", OVERWORLD)) {
                OVERWORLD -> overworld(setup.recipe.caveDensity)
                CAVERNS -> caverns
                NONE -> emptyList()
                else -> throw IllegalArgumentException("Pass 'carvers' has no default set named '$defaults'")
            }
        }
        val carvers = rules.mapIndexed { slot, rule -> registry.create(rule, setup.services, slot) }
        return WorldgenPass { chunk ->
            chunk.ensureSurface()
            carvers.forEach { it.carve(chunk) }
        }
    }

    companion object {
        const val OVERWORLD = "overworld"
        const val CAVERNS = "caverns"
        const val NONE = "none"

        /**
         * Pockets of cave and a few long tunnels, all kept well under the
         * ground: a hillside that opens into holes is a hillside nobody can
         * walk across.
         */
        fun overworld(caveDensity: Float?) = listOf(
            CarverRule(CarverRegistry.CAVES, minZ = 2, maxZ = Chunk.HEIGHT - 1, amount = caveDensity ?: 0.66f, size = 2.4f, headroom = 4),
            CarverRule(CarverRegistry.TUNNELS, minZ = 3, maxZ = 18, amount = 0.8f, size = 1.6f, headroom = 4),
        )

        /**
         * Terraria's underground in a short column: a deep cavern layer, a
         * shallower one with rock between them, tunnels threading both, and
         * small caves near the crust.
         */
        val caverns = listOf(
            CarverRule(CarverRegistry.CAVERNS, minZ = 2, maxZ = 15, amount = 0.52f, size = 6f, headroom = 0),
            CarverRule(CarverRegistry.CAVERNS, minZ = 17, maxZ = 30, amount = 0.56f, size = 4.5f, headroom = 4),
            CarverRule(CarverRegistry.TUNNELS, minZ = 3, maxZ = 32, amount = 1.6f, size = 1.8f, headroom = 5),
            CarverRule(CarverRegistry.CAVES, minZ = 24, maxZ = Chunk.HEIGHT - 1, amount = 0.68f, size = 2f, headroom = 5),
        )
    }
}
