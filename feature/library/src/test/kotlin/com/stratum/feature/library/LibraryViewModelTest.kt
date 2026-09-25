package com.stratum.feature.library

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportOutcome
import com.stratum.core.domain.importing.ImportedPackRepository
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.world.TerrainRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private class FakeRepository : ImportedPackRepository {
        override val packs = MutableStateFlow(emptyList<ContentPack>())
        var failWith: String? = null
        override suspend fun refresh() = Unit
        override suspend fun import(name: String, bytes: ByteArray): ImportOutcome {
            failWith?.let { throw ImportException(it) }
            val pack = ContentPack(
                id = name, name = "Forest Quest", author = "x",
                maps = listOf(TileMap("m", "M", 1, 1, emptyList(), groundBlockId = "g")),
                terrain = TerrainRecipe.tileMap("m"),
            )
            packs.value = packs.value + pack
            return ImportOutcome(pack, "Flame game", listOf("one warning"))
        }
        override suspend fun delete(packId: String) {
            packs.value = packs.value.filterNot { it.id == packId }
        }
    }

    private val repository = FakeRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an import is listed and its warnings are shown`() {
        val viewModel = LibraryViewModel(repository)
        viewModel.importArchive("forest", ByteArray(0))

        val state = viewModel.state.value
        val status = assertIs<ImportStatus.Imported>(state.status)
        assertEquals(listOf("one warning"), status.warnings)
        assertEquals("Flame game", status.importerName)
        assertEquals(true, state.packs.single().replacesWorld)
        assertEquals("1 level · plays on its own level", describe(state.packs.single()))
    }

    @Test
    fun `a failed import says why`() {
        repository.failWith = "no maps"
        val viewModel = LibraryViewModel(repository)
        viewModel.importArchive("broken", ByteArray(0))

        assertEquals(ImportStatus.Failed("no maps"), viewModel.state.value.status)
    }

    @Test
    fun `removing a pack drops it from the list`() {
        val viewModel = LibraryViewModel(repository)
        viewModel.importArchive("forest", ByteArray(0))
        viewModel.delete("forest")

        assertEquals(emptyList(), viewModel.state.value.packs)
    }
}
