package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteFallbackTest {

    private val sheets = listOf("a", "b", "c", "d")

    @Test
    fun `different actors prefer different art`() {
        // The bug this exists for: four kinds of monster, four sheets, and
        // every one of them drawn with the same picture because they all took
        // the first candidate.
        val preferred = listOf("rat", "ghoul", "brute", "leopard")
            .map { SpriteFallback.spread(sheets, it).first() }

        assertTrue(
            preferred.toSet().size > 1,
            "every actor still resolved to the same sheet: $preferred",
        )
    }

    @Test
    fun `an actor gets the same art every time it is asked`() {
        // Asked on every drawn frame. A choice that moved would be a monster
        // flickering between two bodies.
        val once = SpriteFallback.spread(sheets, "ghoul")
        repeat(8) { assertEquals(once, SpriteFallback.spread(sheets, "ghoul")) }
    }

    @Test
    fun `nothing is dropped, so a blank sheet still falls through`() {
        // The order after the starting point is what makes the second choice
        // meaningful: a sheet that decodes to nothing has to hand on to the
        // next one, exactly as it did before any of this.
        val spread = SpriteFallback.spread(sheets, "brute")
        assertEquals(sheets.size, spread.size)
        assertEquals(sheets.toSet(), spread.toSet())
        // Rotated rather than shuffled: the run after the start is in order.
        val at = sheets.indexOf(spread.first())
        assertEquals(sheets.subList(at, sheets.size) + sheets.subList(0, at), spread)
    }

    @Test
    fun `one sheet or none is left exactly as it is`() {
        assertEquals(emptyList(), SpriteFallback.spread(emptyList<String>(), "rat"))
        assertEquals(listOf("only"), SpriteFallback.spread(listOf("only"), "rat"))
    }
}
