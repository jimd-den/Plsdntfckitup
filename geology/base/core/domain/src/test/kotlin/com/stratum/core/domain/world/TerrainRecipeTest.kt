package com.stratum.core.domain.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainRecipeTest {

    @Test
    fun `no clustering leaves every place equally likely`() {
        val even = TerrainRecipe(scatterClustering = 0f)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { sample ->
            assertEquals(1f, even.scatterDensity(sample), 0.0001f)
        }
    }

    @Test
    fun `clustering makes thin places actually thin`() {
        // The bug this pins: the first version fed fractal noise straight in,
        // and fractal noise is an average of octaves, so it spends almost all
        // of its time near the middle. The result was a field slightly thicker
        // in places and slightly thinner in others -- which is the even
        // sprinkle it was meant to replace. Rendered side by side with no
        // clustering at all, the two were indistinguishable.
        val recipe = TerrainRecipe(scatterClustering = 0.75f)

        // The noise's real fifth and ninety-fifth percentiles, measured.
        val thin = recipe.scatterDensity(0.25f)
        val thick = recipe.scatterDensity(0.79f)

        assertTrue(thin < 0.35f, "a clearing still had $thin of the usual scatter")
        assertTrue(thick > 1.6f, "a grove only had $thick of the usual scatter")
    }

    @Test
    fun `clustering redistributes rather than stripping or burying`() {
        // Turning clustering up must not quietly empty the world or fill it:
        // what the clearings lose, the groves have to gain.
        val recipe = TerrainRecipe(scatterClustering = 0.75f)
        // Sampled evenly across the range the noise actually occupies.
        val samples = (0..100).map { 0.25f + (0.79f - 0.25f) * it / 100f }
        val mean = samples.map { recipe.scatterDensity(it) }.average().toFloat()
        assertTrue(mean in 0.85f..1.15f, "average scatter moved to $mean of what was asked for")
    }

    @Test
    fun `a share outside zero to one is refused rather than clamped`() {
        // Clamping would silently accept a recipe that says something
        // impossible, and the author would never learn their pack was ignored.
        runCatching { TerrainRecipe(scatterClustering = 1.4f) }
            .onSuccess { error("a clustering of 1.4 was accepted") }
        runCatching { TerrainRecipe(scatterClusterScale = 0f) }
            .onSuccess { error("a cluster scale of zero was accepted") }
    }
}
