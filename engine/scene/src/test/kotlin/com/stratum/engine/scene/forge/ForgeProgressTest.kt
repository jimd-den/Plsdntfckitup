package com.stratum.engine.scene.forge

import com.stratum.core.domain.art.AssetKind
import com.stratum.core.domain.art.AssetTier
import com.stratum.core.domain.art.ForgeOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgeProgressTest {

    private fun order(key: String, kind: AssetKind = AssetKind.GROUND_TILE) =
        ForgeOrder(key, kind, AssetTier.entries.first(), subject = key, prompt = key)

    @Test
    fun `progress counts successes, failures and skips toward the whole`() {
        val progress = ForgeProgress(total = 4, skipped = 1)
            .recording(ForgedAsset(order("a"), texture = null, failure = "rejected: flat"))
            .recording(ForgedAsset(order("b"), texture = com.stratum.engine.scene.Texture(1, 1, IntArray(1))))

        assertEquals(3, progress.done)
        assertEquals(0.75f, progress.fraction)
        assertFalse(progress.isFinished)
        assertEquals("3 of 4 · 1 failed · 1 already made", progress.summary)
        assertEquals(listOf("b"), progress.latest.map { it.key })
    }

    @Test
    fun `a cancelled run is finished however far it got`() {
        assertTrue(ForgeProgress(total = 10).cancelling().isFinished)
        assertEquals(1f, ForgeProgress(total = 0).fraction)
    }

    @Test
    fun `a plan describes itself by kind`() {
        val plan = listOf(order("g1"), order("g2"), order("w", AssetKind.WALL_TILE), order("p", AssetKind.PROP_SPRITE))

        assertEquals("2 ground, 1 wall, 1 prop", ForgeProgress.describe(plan))
    }
}
