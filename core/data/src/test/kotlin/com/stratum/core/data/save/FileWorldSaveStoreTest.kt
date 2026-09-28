package com.stratum.core.data.save

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.RealmSave
import com.stratum.core.domain.session.SavedChunk
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldPlayer
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileWorldSaveStoreTest {

    private val root: File = Files.createTempDirectory("worlds").toFile()
    private val skipped = mutableListOf<File>()
    private val store = FileWorldSaveStore(root, Dispatchers.Unconfined) { file, _ -> skipped += file }

    private fun chunk(x: Int, y: Int, fill: Short): SavedChunk =
        SavedChunk(x, y, ShortArray(Chunk.VOLUME) { i -> if (i < Chunk.SIZE * Chunk.SIZE * 10) fill else 0 }.also { it[Chunk.VOLUME - 1] = 3 })

    private fun save(
        id: String = "w1",
        lastPlayedAt: Long = 100L,
        chunks: List<SavedChunk> = listOf(chunk(0, 0, 2), chunk(-3, 7, 4)),
    ) = WorldSave(
        identity = WorldIdentity(id, "Riverlands", createdAt = 5L, presetName = "Conqueror", packIds = listOf("igbo", "stratum:forged"), heroName = "Ada"),
        lastPlayedAt = lastPlayedAt,
        playSeconds = 3723L,
        config = WorldConfig(
            seed = -9_000_000_000L, simulationRadius = 3, surfaceVariation = 6,
            rules = WorldRules(survival = SurvivalMode.HARSH, townDensity = 1.8f, raids = false, dayLengthMinutes = 30f, combat = CombatRules.UNBOUND),
        ),
        difficulty = Difficulty(2, listOf(WaystoneMod("m", "Brutal", monster = listOf(StatModifier(Stat.MAX_HEALTH, ModifierKind.INCREASED, 0.4f))))),
        hero = HeroSave(
            "igbo:dibia", "igbo:dibia", level = 12, experience = 340, passives = setOf("a", "b"), currency = mapOf("orb" to 3),
            bag = listOf(ItemInstance("i1", "igbo:machete", "Machete", ItemRarity.UNCOMMON, 10, ItemSlot.WEAPON, minDamage = 3, maxDamage = 7)),
            savedAt = 100L,
        ),
        player = WorldPlayer(
            WorldPoint(12.5f, -40.25f, 17f), Direction.WEST, health = 44, resource = 9, needs = mapOf("hunger" to 61.5f),
            inventory = mapOf("igbo:laterite" to 30), hotbar = listOf("igbo:laterite"), selectedSlot = 0, toolTier = 2,
        ),
        clockSeconds = 1234.5f,
        blockIds = listOf("stratum:air", "stratum:bedrock", "igbo:laterite", "igbo:clay", "igbo:iron"),
        chunks = chunks,
        realm = RealmSave(
            outposts = listOf(Outpost("o1", "Hold", 10, -4, structures = mapOf("hearth" to 1), stockpile = mapOf("stone" to 12.5f), garrison = mapOf("militia" to 2), raidIn = 99f)),
            followerOrder = FollowerOrder.HOLD, holdAt = WorldPoint(1f, 2f, 3f), followers = listOf("militia", "militia"), liberatedTowns = setOf("town_3"),
        ),
        consumedMarkers = setOf("BOSS@6,0,13#crypt", "LOOT@7,0,13#crypt"),
    )

    @Test
    fun `a world reads back exactly as it was written`() = runBlocking<Unit> {
        val original = save()
        store.save(original)
        assertEquals(original, store.load("w1"))
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `chunks are stored deflated, far smaller than their cells`() = runBlocking<Unit> {
        store.save(save())
        val blob = File(root, "w1").listFiles()!!.single { it.name.endsWith(".bin") }
        assertTrue(blob.length() < Chunk.VOLUME * 2 / 10, "two chunks took ${blob.length()} bytes")
    }

    @Test
    fun `a world with no changed chunks writes no chunk file and reads back empty`() = runBlocking<Unit> {
        store.save(save(chunks = emptyList()))
        assertTrue(File(root, "w1").listFiles()!!.none { it.name.endsWith(".bin") })
        assertEquals(emptyList(), store.load("w1")!!.chunks)
    }

    @Test
    fun `saving again replaces the chunks and leaves one chunk file and no temporary files`() = runBlocking<Unit> {
        store.save(save())
        val later = save(lastPlayedAt = 200L, chunks = listOf(chunk(9, 9, 3)))
        store.save(later)

        assertEquals(later, store.load("w1"))
        val files = File(root, "w1").list()!!.sorted()
        assertEquals(1, files.count { it.endsWith(".bin") }, "files: $files")
        assertTrue(files.none { it.endsWith(".tmp") }, "files: $files")
    }

    @Test
    fun `the list reads summaries, newest played first`() = runBlocking<Unit> {
        store.save(save("old", lastPlayedAt = 10L))
        store.save(save("new", lastPlayedAt = 30L))
        store.save(save("mid", lastPlayedAt = 20L))

        val listed = store.list()
        assertEquals(listOf("new", "mid", "old"), listed.map { it.id })
        assertEquals(save("new", lastPlayedAt = 30L).summary(), listed.first())
    }

    @Test
    fun `listing does not read the state or the chunks`() = runBlocking<Unit> {
        store.save(save())
        File(root, "w1/world.json").writeText("not json")
        File(root, "w1").listFiles()!!.filter { it.name.endsWith(".bin") }.forEach { it.writeText("junk") }
        assertEquals(listOf("w1"), store.list().map { it.id })
    }

    @Test
    fun `a corrupt world is skipped and logged, and the others still read`() = runBlocking<Unit> {
        store.save(save("good"))
        store.save(save("bad"))
        File(root, "bad/summary.json").writeText("{ truncated")
        File(root, "bad/world.json").writeText("{\"id\": 3")

        assertEquals(listOf("good"), store.list().map { it.id })
        assertNull(store.load("bad"))
        assertNotNull(store.load("good"))
        assertEquals(2, skipped.size)
    }

    @Test
    fun `a world whose chunk file is damaged does not load half a world`() = runBlocking<Unit> {
        store.save(save())
        File(root, "w1").listFiles()!!.single { it.name.endsWith(".bin") }.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.load("w1"))
        assertEquals(1, skipped.size)
    }

    @Test
    fun `a save from a newer format is refused rather than misread`() = runBlocking<Unit> {
        store.save(save())
        val state = File(root, "w1/world.json")
        state.writeText(state.readText().replace("\"version\":1", "\"version\":99").let { if ("\"version\"" in it) it else it.replaceFirst("{", "{\"version\":99,") })
        assertNull(store.load("w1"))
    }

    @Test
    fun `rename changes the name everywhere and keeps the world`() = runBlocking<Unit> {
        val original = save()
        store.save(original)
        store.rename("w1", "Home")

        assertEquals("Home", store.list().single().name)
        assertEquals(original.copy(identity = original.identity.copy(name = "Home")), store.load("w1"))
    }

    @Test
    fun `delete removes the whole world, and unknown ids are harmless`() = runBlocking<Unit> {
        store.save(save("a"))
        store.save(save("b"))
        store.delete("a")
        store.delete("nobody")
        store.rename("nobody", "x")

        assertEquals(listOf("b"), store.list().map { it.id })
        assertNull(store.load("a"))
        assertTrue(!File(root, "a").exists())
    }

    @Test
    fun `an id of dots never reaches outside the worlds folder`() = runBlocking<Unit> {
        val sibling = File(root.parentFile, "keep-${root.name}").apply { mkdirs() }
        store.delete("..")
        assertTrue(sibling.exists() && root.exists())
        assertNull(store.load(".."))
        sibling.delete()
    }

    @Test
    fun `ids are made safe for the file system`() = runBlocking<Unit> {
        store.save(save("pack:world/one"))
        assertEquals("pack:world/one", store.list().single().id)
        assertNotNull(store.load("pack:world/one"))
    }
}
