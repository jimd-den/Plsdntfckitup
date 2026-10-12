package com.stratum.app

import com.stratum.app.nav.BackStack
import com.stratum.app.nav.Route
import com.stratum.app.world.NewWorldDraft
import com.stratum.app.world.NewWorldStep
import com.stratum.app.world.WorldFormat
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldSummary
import com.stratum.core.domain.world.RulesPresets
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShellTest {

    @Test
    fun `back goes up one level and never past the title`() {
        val stack = BackStack()
        stack.push(Route.Create.Hub)
        stack.push(Route.Create.Masks)
        stack.push(Route.Create.Masks)
        assertEquals(listOf(Route.Title, Route.Create.Hub, Route.Create.Masks), stack.routes)
        assertTrue(stack.pop())
        assertTrue(stack.pop())
        assertFalse(stack.pop())
        assertEquals(Route.Title, stack.current)
    }

    @Test
    fun `leaving a world continued from the title lands on the play hub`() {
        val stack = BackStack()
        stack.push(Route.Play.World(com.stratum.app.world.WorldLaunch.Resume("w1", null)))
        stack.popTo(Route.Play.Hub)
        assertEquals(listOf(Route.Title, Route.Play.Hub), stack.routes)
    }

    @Test
    fun `leaving a world started from the hub pops back to that hub`() {
        val stack = BackStack()
        stack.push(Route.Play.Hub)
        stack.push(Route.Play.NewWorld)
        stack.replace(Route.Play.World(com.stratum.app.world.WorldLaunch.Resume("w1", null)))
        stack.popTo(Route.Play.Hub)
        assertEquals(listOf(Route.Title, Route.Play.Hub), stack.routes)
    }

    @Test
    fun `a new world steps forward and back through its three steps`() {
        val draft = NewWorldDraft()
        assertEquals(NewWorldStep.WORLD, draft.next().step)
        assertEquals(NewWorldStep.GO, draft.next().next().next().step)
        assertEquals(NewWorldStep.HERO, draft.back().step)
    }

    @Test
    fun `typed seeds are stable and blank seeds take the roll`() {
        assertEquals(42L, NewWorldDraft(seedText = "42").seed(7L))
        assertEquals(7L, NewWorldDraft(seedText = "  ").seed(7L))
        assertEquals(NewWorldDraft(seedText = "river").seed(1L), NewWorldDraft(seedText = "river").seed(2L))
        assertNotEquals(NewWorldDraft(seedText = "river").seed(1L), NewWorldDraft(seedText = "rivet").seed(1L))
    }

    @Test
    fun `a draft launches into the library's slot with its preset rules`() {
        val draft = NewWorldDraft().choosePreset(RulesPresets.survivor)
        val launch = draft.launch(WorldIdentity("slot", "Ash"), defaultHeroClassId = "dike", fallbackSeed = 9L)
        assertEquals("slot", launch.worldId)
        assertEquals("dike", launch.heroClassId)
        assertEquals(RulesPresets.survivor.rules, launch.rules)
        assertEquals("Survivor", draft.presetLabel())
        assertEquals("", draft.copy(rules = draft.rules.copy(raids = !draft.rules.raids)).presetLabel())
    }

    @Test
    fun `cards say play time and last played in words`() {
        assertEquals("4h 12m", WorldFormat.playTime(4 * 3600 + 12 * 60))
        assertEquals("under a minute", WorldFormat.playTime(20))
        val now = 10_000_000_000L
        assertEquals("just now", WorldFormat.lastPlayed(now - 1000, now))
        assertEquals("2 hours ago", WorldFormat.lastPlayed(now - 2 * 3_600_000L, now))
        assertEquals("yesterday", WorldFormat.lastPlayed(now - 30 * 3_600_000L, now))
    }

    @Test
    fun `the in-memory repository lists newest played first and renames in place`() = runBlocking {
        fun world(id: String, at: Long) = WorldSummary(id, id, "Hero", "c", 1, "Adventure", 1L, 0L, at, 0L)
        val repository = InMemoryWorldSaveRepository(listOf(world("old", 1L), world("new", 5L)))
        assertEquals(listOf("new", "old"), repository.list().map { it.id })
        repository.rename("old", "Renamed")
        assertEquals("Renamed", repository.list().last().name)
        repository.delete("new")
        assertEquals(listOf("old"), repository.list().map { it.id })
    }
}
