package com.stratum.engine.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainLayerPolicyTest {

    private val director = Any()
    private fun key(revision: Int = 1, dayStep: Int = 10, highlight: Any? = null) = TerrainLayerPolicy.Key(
        worldRevision = revision, width = 1080, height = 2340, zoom = 1f, eyeLevel = 40,
        biomeId = "grove", dayStep = dayStep, underground = false, highlight = highlight, director = director,
    )

    @Test
    fun `nothing is painted until the first frame asks`() {
        assertTrue(TerrainLayerPolicy(margin = 200f).needsPaint(key(), 0f, 0f))
    }

    @Test
    fun `a still camera over an unchanged world reuses the layer`() {
        val policy = TerrainLayerPolicy(margin = 200f)
        policy.painted(key(), 100f, 100f)
        assertFalse(policy.needsPaint(key(), 100f, 100f))
    }

    @Test
    fun `walking within the margin slides the layer instead of repainting it`() {
        val policy = TerrainLayerPolicy(margin = 200f)
        policy.painted(key(), 100f, 100f)
        assertFalse(policy.needsPaint(key(), 250f, -80f))
        // Painted with its origin moved in by the margin, so it sits the margin up and left, plus the walk.
        assertEquals(150f - 200f, policy.layerOffsetX(250f))
        assertEquals(-180f - 200f, policy.layerOffsetY(-80f))
    }

    @Test
    fun `walking past the margin repaints`() {
        val policy = TerrainLayerPolicy(margin = 200f)
        policy.painted(key(), 100f, 100f)
        assertTrue(policy.needsPaint(key(), 301f, 100f))
    }

    @Test
    fun `an edit, a new hour of light or a new highlight repaints`() {
        val policy = TerrainLayerPolicy(margin = 200f)
        policy.painted(key(), 0f, 0f)
        assertTrue(policy.needsPaint(key(revision = 2), 0f, 0f))
        assertTrue(policy.needsPaint(key(dayStep = 11), 0f, 0f))
        assertTrue(policy.needsPaint(key(highlight = "cell"), 0f, 0f))
    }

    @Test
    fun `the day is cut into steps and wraps`() {
        val policy = TerrainLayerPolicy(margin = 1f)
        assertEquals(0, policy.dayStep(0f))
        assertEquals(policy.dayStep(0.25f), policy.dayStep(1.25f))
        assertTrue(policy.dayStep(0.999f) < TerrainLayerPolicy.DAY_BUCKETS)
    }
}
