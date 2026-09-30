package com.stratum.feature.play

import com.stratum.core.domain.content.PackPalette
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.IsometricProjection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayHintsTest {

    private fun state(buildMode: Boolean = false) = PlayUiState(
        player = PlayerState("t:hero", WorldPoint(0f, 0f, 0f)),
        camera = WorldPoint(0f, 0f, 0f),
        projection = IsometricProjection(),
        palette = PackPalette(),
        biomeName = "",
        buildMode = buildMode,
    )

    @Test
    fun `hints teach the basics first, one at a time, and each only once`() {
        assertEquals(Hints.MOVE, nextHint(state(), emptySet())?.id)
        assertEquals(Hints.STRIKE, nextHint(state(), setOf(Hints.MOVE))?.id)
        assertNull(nextHint(state(), Hints.all))
    }

    @Test
    fun `entering build mode teaches building before anything else`() {
        assertEquals(Hints.BUILD, nextHint(state(buildMode = true), emptySet())?.id)
        assertEquals(Hints.MOVE, nextHint(state(buildMode = true), setOf(Hints.BUILD))?.id)
    }

    @Test
    fun `the menu button carries the badge of whatever inside is calling`() {
        val quiet = DockEntry("b", "Bag", {}, badge = "3")
        val hero = DockEntry("h", "Hero", {}, badge = "+2", calling = true)
        assertNull(menuBadge(listOf(quiet)))
        assertEquals("+2", menuBadge(listOf(quiet, hero)))
        assertEquals("!", menuBadge(listOf(DockEntry("c", "Camp", {}, calling = true))))
    }
}
