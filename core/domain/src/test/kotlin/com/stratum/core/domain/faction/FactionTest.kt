package com.stratum.core.domain.faction

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactionTest {

    private val guard = StatModifier(Stat.ARMOUR, ModifierKind.INCREASED, 0.1f)
    private val imperium = FactionDefinition(
        id = "w:imperium", name = "Imperium",
        relations = mapOf("w:chaos" to Stance.HOSTILE),
        ranks = listOf(ReputationRank("Acolyte", 50), ReputationRank("Inquisitor", 300, listOf(guard))),
    )
    private val chaos = FactionDefinition(id = "w:chaos", name = "Chaos", defaultStance = Stance.HOSTILE, startingStanding = -200)
    private val book = FactionBook(listOf(imperium, chaos))

    @Test
    fun `stances come from standing, and wild things are always hostile`() {
        val reputation = Reputation()

        assertEquals(Stance.NEUTRAL, book.stanceToPlayer(imperium.id, reputation))
        assertEquals(Stance.HOSTILE, book.stanceToPlayer(chaos.id, reputation))
        assertEquals(Stance.HOSTILE, book.stanceToPlayer(null, reputation))
    }

    @Test
    fun `killing a faction's people costs standing with them and earns it with their enemies`() {
        val after = (1..20).fold(Reputation()) { rep, _ -> rep.afterKilling(chaos.id, book) }

        assertEquals(-200 - 20 * Reputation.KILL_PENALTY, after.of(chaos))
        assertEquals(20 * Reputation.KILL_REWARD, after.of(imperium))
    }

    @Test
    fun `ranks grant their modifiers while held`() {
        val high = Reputation().adjusted(imperium.id, 320, book)

        assertEquals("Inquisitor", imperium.rankFor(high.of(imperium))?.name)
        assertEquals(listOf(guard), high.modifiers(book))
        assertEquals(Stance.ALLIED, high.stanceOf(imperium))
    }

    @Test
    fun `factions at war fight each other, and relations must name real factions`() {
        assertTrue(book.hostile(imperium.id, chaos.id))
        assertFalse(book.hostile(imperium.id, imperium.id))
        assertTrue(book.hostile(null, imperium.id))
        assertEquals(1, FactionBook(listOf(imperium)).problems().size)
    }
}
