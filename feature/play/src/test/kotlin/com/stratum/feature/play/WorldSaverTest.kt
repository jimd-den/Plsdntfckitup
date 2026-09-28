package com.stratum.feature.play

import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldPlayer
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import com.stratum.core.domain.session.WorldSummary
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldSaverTest {

    private class Shelf : WorldSaveRepository {
        val saved = mutableListOf<WorldSave>()
        var gate: CompletableDeferred<Unit>? = null
        var failNext = false

        override suspend fun list(): List<WorldSummary> = saved.map { it.summary() }
        override suspend fun load(id: String): WorldSave? = saved.lastOrNull { it.id == id }
        override suspend fun save(save: WorldSave) {
            gate?.await()
            if (failNext) {
                failNext = false
                error("disk full")
            }
            saved += save
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun rename(id: String, name: String) = Unit
    }

    private val shelf = Shelf()
    private val heroes = mutableListOf<HeroSave>()
    private val notices = mutableListOf<SaveNotice>()
    private val failures = mutableListOf<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saver = WorldSaver(shelf, { synchronized(heroes) { heroes += it } }, scope, { synchronized(notices) { notices += it } }, { failures += it })

    private fun world(level: Int, sandbox: Boolean = false) = WorldSave(
        identity = WorldIdentity("w", "World"),
        config = WorldConfig(rules = WorldRules(sandbox = sandbox)),
        hero = HeroSave("h", "t:hero", level = level),
        player = WorldPlayer(WorldPoint(0f, 0f, 0f), health = 1, resource = 1),
    )

    @Test
    fun `an autosave falls due each minute of play and on every level`() {
        saver.mark(elapsed = 0f, level = 1)
        assertNull(saver.due(30f, 1))
        assertEquals(SaveReason.AUTOSAVE, saver.due(60f, 1))
        assertEquals(SaveReason.LEVEL_UP, saver.due(5f, 2))

        saver.write(null, null, sandbox = false, reason = SaveReason.AUTOSAVE, elapsed = 60f, level = 2)
        assertNull(saver.due(100f, 2), "writing resets the minute")
        assertEquals(SaveReason.AUTOSAVE, saver.due(120f, 2))
    }

    @Test
    fun `a save writes the world to its slot and the hero to the roster, then says so`() = runBlocking<Unit> {
        val save = world(3)
        saver.write(save, save.hero, sandbox = false, reason = SaveReason.AUTOSAVE, elapsed = 60f, level = 3).join()

        assertEquals(listOf(save), shelf.saved)
        assertEquals(listOf(save.hero), heroes)
        assertEquals(listOf(SaveNotice(1, SaveReason.AUTOSAVE)), notices)
    }

    @Test
    fun `a sandbox world keeps its slot but never touches the real hero`() = runBlocking<Unit> {
        val save = world(90, sandbox = true)
        saver.write(save, save.hero, sandbox = true, reason = SaveReason.EXIT, elapsed = 1f, level = 90).join()

        assertEquals(listOf(save), shelf.saved)
        assertTrue(heroes.isEmpty())
    }

    @Test
    fun `without a slot the hero is still kept, as before worlds were saved`() = runBlocking<Unit> {
        val loose = WorldSaver(null, { heroes += it }, scope)
        loose.write(world(2), HeroSave("h", "t:hero", level = 2), sandbox = false, reason = SaveReason.AUTOSAVE, elapsed = 1f, level = 2).join()
        assertEquals(1, heroes.size)
    }

    @Test
    fun `saves land in the order they were taken, one at a time`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>().also { shelf.gate = it }
        val jobs = (1..4).map { level -> saver.write(world(level), null, false, SaveReason.AUTOSAVE, level.toFloat(), level) }
        gate.complete(Unit)
        jobs.joinAll()

        assertEquals(listOf(1, 2, 3, 4), shelf.saved.map { it.hero.level })
        assertEquals(listOf(1, 2, 3, 4), notices.map { it.serial })
    }

    @Test
    fun `a failed write is reported and the next one still lands`() = runBlocking<Unit> {
        shelf.failNext = true
        saver.write(world(1), null, false, SaveReason.AUTOSAVE, 1f, 1).join()
        saver.write(world(2), null, false, SaveReason.AUTOSAVE, 2f, 2).join()

        assertEquals(1, failures.size)
        assertEquals(listOf(2), shelf.saved.map { it.hero.level })
    }
}
