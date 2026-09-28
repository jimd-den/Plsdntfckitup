package com.stratum.core.data.save

import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.WorldPlayer
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldLibraryTest {

    private var ids = 0
    private val library = WorldLibrary(
        FileWorldSaveStore(Files.createTempDirectory("library").toFile(), Dispatchers.Unconfined),
        clock = { 1_000L },
        newId = { "world-${++ids}" },
    )

    private fun started(name: String, playedAt: Long): WorldSave = WorldSave(
        identity = library.create(name, presetName = "Adventure", heroName = "Ada"),
        lastPlayedAt = playedAt,
        config = WorldConfig(seed = 3L),
        hero = HeroSave("h", "t:hero"),
        player = WorldPlayer(WorldPoint(0f, 0f, 0f), health = 1, resource = 1),
    )

    @Test
    fun `a new world is named and stamped but not written until it is played`() = runBlocking<Unit> {
        val identity = library.create("  My   world ")
        assertEquals("world-1", identity.id)
        assertEquals("My world", identity.name)
        assertEquals(1_000L, identity.createdAt)
        assertEquals(WorldLibrary.DEFAULT_NAME, library.create("   ").name)
        assertEquals(emptyList(), library.list())
    }

    @Test
    fun `continue opens the world played last`() = runBlocking<Unit> {
        assertNull(library.latest())
        library.repository.save(started("First", playedAt = 10L))
        val second = started("Second", playedAt = 20L)
        library.repository.save(second)

        assertEquals(second, library.latest())
    }

    @Test
    fun `renaming tidies the name and ignores a blank one`() = runBlocking<Unit> {
        val world = started("First", playedAt = 10L)
        library.repository.save(world)
        library.rename(world.id, "  Home  ")
        library.rename(world.id, " ")
        assertEquals("Home", library.list().single().name)

        library.delete(world.id)
        assertEquals(emptyList(), library.list())
    }
}
