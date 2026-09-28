package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.ClimatePoint
import com.stratum.core.domain.world.ClimateSpec

/**
 * Biomes chosen by climate: two smooth noise fields, heat and wet, and each
 * column is the biome whose [ClimatePoint] is nearest the climate there.
 *
 * Heights blend by the same distance. Every biome within [ClimateSpec.blend]
 * of the nearest one in climate lends it some of its height bias and
 * roughness, weighted by how close it is, so a border between a high region
 * and a low one is a slope as wide as the climate takes to cross the blend --
 * never a wall. Because the weights are continuous in climate and climate is
 * continuous in space, the ground is too, with no grid to snap to.
 */
class ClimateMap(services: WorldServices, spec: ClimateSpec?) : BiomeMap {

    private val spec = spec ?: ClimateSpec()

    private val heat = services.noise(HEAT_SALT)
    private val wet = services.noise(WET_SALT)

    private val surface: Array<Placed>
    private val underground: Array<Placed>

    /** A biome at its place in climate. */
    private class Placed(val biome: BiomeDefinition, val temperature: Float, val moisture: Float, val minDepth: Int, val maxDepth: Int)

    init {
        val biomes = services.context.biomes
        require(biomes.isNotEmpty()) { "Terrain generation needs at least one biome" }
        val explicit = this.spec.points.groupBy(ClimatePoint::biomeId)
        val implicit = biomes.filter { it.id !in explicit }
        val placed = biomes.flatMap { biome ->
            explicit[biome.id]?.map { p -> Placed(biome, p.temperature, p.moisture, p.minDepth ?: 0, p.maxDepth ?: 0) }
                ?: run {
                    // A biome the pack placed nowhere keeps its own temperature
                    // and takes an even share of the moisture axis, so every
                    // region of an older pack still appears somewhere.
                    val slot = implicit.indexOf(biome)
                    listOf(Placed(biome, biome.temperature.coerceIn(0f, 1f), (slot + 0.5f) / implicit.size, 0, 0))
                }
        }
        surface = placed.filter { it.maxDepth == 0 }.toTypedArray()
        underground = placed.filter { it.maxDepth > 0 }.toTypedArray()
        require(surface.isNotEmpty()) { "Every biome is underground; the surface needs at least one" }
    }

    override val hasUnderground: Boolean get() = underground.isNotEmpty()

    override fun sample(x: Int, y: Int, out: ClimateSample): ClimateSample {
        val t = stretched(heat.fractal(x * spec.scale, y * spec.scale, octaves = 2))
        val m = stretched(wet.fractal(x * spec.scale + WET_SHIFT, y * spec.scale - WET_SHIFT, octaves = 2))
        out.temperature = t
        out.moisture = m
        if (surface.size == 1) {
            val only = surface[0].biome
            out.biome = only
            out.bias = only.heightBias.toFloat()
            out.roughness = only.roughness
            return out
        }

        var nearest = 0
        var nearestDistance = Float.MAX_VALUE
        for (i in surface.indices) {
            val d = distance(surface[i], t, m)
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = i
            }
        }
        // Weights fall from 1 at the nearest biome to 0 at the blend's edge,
        // eased so the slope has no kink where a neighbour starts to count.
        var weight = 0f
        var bias = 0f
        var roughness = 0f
        for (i in surface.indices) {
            val gap = (distance(surface[i], t, m) - nearestDistance) / spec.blend
            if (gap >= 1f) continue
            val w = 1f - gap * gap * (3f - 2f * gap)
            weight += w
            bias += w * surface[i].biome.heightBias
            roughness += w * surface[i].biome.roughness
        }
        out.biome = surface[nearest].biome
        out.bias = bias / weight
        out.roughness = roughness / weight
        return out
    }

    override fun undergroundAt(climate: ClimateSample, depth: Int): BiomeDefinition? {
        var best: Placed? = null
        var bestDistance = Float.MAX_VALUE
        for (p in underground) {
            if (depth < p.minDepth || depth > p.maxDepth) continue
            val d = distance(p, climate.temperature, climate.moisture)
            if (d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        return best?.biome
    }

    private fun distance(p: Placed, t: Float, m: Float): Float {
        val dt = p.temperature - t
        val dm = p.moisture - m
        return kotlin.math.sqrt(dt * dt + dm * dm)
    }

    /**
     * Fractal value noise spends nearly all its time near the middle, which
     * would leave the biomes at the corners of climate space almost never
     * chosen. Stretched so the whole range is used.
     */
    private fun stretched(sample: Float): Float = ((sample - 0.5f) * SPREAD + 0.5f).coerceIn(0f, 1f)

    private companion object {
        const val HEAT_SALT = 7_331L
        const val WET_SALT = 9_137L
        const val WET_SHIFT = 517.3f
        const val SPREAD = 2.2f
    }
}
