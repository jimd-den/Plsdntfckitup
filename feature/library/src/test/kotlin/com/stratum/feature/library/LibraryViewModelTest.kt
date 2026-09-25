package com.stratum.feature.library

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportOutcome
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.plugin.InstalledPlugin
import com.stratum.core.domain.plugin.PluginDependency
import com.stratum.core.domain.plugin.PluginLibrary
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginOrder
import com.stratum.core.domain.plugin.PluginRepository
import com.stratum.core.domain.plugin.PluginResolver
import com.stratum.core.domain.plugin.Version
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    /** An in-memory repository that resolves the way the real one does. */
    private class FakeRepository : PluginRepository {
        override val library = MutableStateFlow(PluginLibrary())
        var failWith: String? = null
        private val installed = mutableListOf<Pair<PluginManifest, ContentPack>>()
        private var order = PluginOrder()

        override suspend fun refresh() = Unit

        override suspend fun install(name: String, bytes: ByteArray): ImportOutcome {
            failWith?.let { throw ImportException(it) }
            val needs = if (name == "campaign") listOf(PluginDependency("monsters")) else emptyList()
            val manifest = PluginManifest(name, name.replaceFirstChar(Char::uppercaseChar), Version(1, 0, 0), author = "Ada", license = "MIT", dependencies = needs)
            val pack = ContentPack(
                id = name, name = name, author = "Ada",
                maps = listOf(TileMap("m", "M", 1, 1, emptyList(), groundBlockId = "g")),
                terrain = TerrainRecipe.tileMap("m"),
            )
            installed += manifest to pack
            publish()
            return ImportOutcome(pack, "Stratum plugin", listOf("one warning"), manifest)
        }

        override suspend fun uninstall(pluginId: String) {
            installed.removeAll { it.first.id == pluginId }
            publish()
        }

        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
            order = order.withEnabled(pluginId, enabled)
            publish()
        }

        override suspend fun move(pluginId: String, by: Int) {
            order = order.moved(pluginId, by)
            publish()
        }

        private fun publish() {
            order = order.reconciledWith(installed.map { it.first.id })
            val plugins = order.ids.map { id -> installed.first { it.first.id == id }.let { InstalledPlugin(it.first, it.second, order.isEnabled(id)) } }
            library.value = PluginLibrary(plugins, PluginResolver.resolve(plugins.map { it.manifest }, order.enabledIds))
        }
    }

    private val repository = FakeRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an install is listed with who made it and its warnings are shown`() {
        val viewModel = LibraryViewModel(repository)
        viewModel.install("monsters", ByteArray(0))

        val state = viewModel.state.value
        val status = assertIs<ImportStatus.Imported>(state.status)
        assertEquals(listOf("one warning"), status.warnings)
        val plugin = state.plugins.single()
        assertTrue(plugin.active)
        assertEquals("by Ada · MIT · loaded", byline(plugin))
        assertEquals("1 level · plays on its own level", describe(plugin))
    }

    @Test
    fun `a plugin whose dependency is switched off says why it is not loading`() {
        val viewModel = LibraryViewModel(repository)
        viewModel.install("monsters", ByteArray(0))
        viewModel.install("campaign", ByteArray(0))
        viewModel.setEnabled("monsters", false)

        val campaign = viewModel.state.value.plugins.single { it.id == "campaign" }
        assertTrue(campaign.enabled)
        assertFalse(campaign.active)
        assertTrue("monsters" in assertNotNull(campaign.problem))
    }

    @Test
    fun `plugins move in the load order and cannot move past the ends`() {
        val viewModel = LibraryViewModel(repository)
        viewModel.install("a", ByteArray(0))
        viewModel.install("b", ByteArray(0))
        viewModel.moveEarlier("b")

        val plugins = viewModel.state.value.plugins
        assertEquals(listOf("b", "a"), plugins.map { it.id })
        assertTrue(plugins.first().isFirst && plugins.last().isLast)
    }

    @Test
    fun `a failed install says why, and removing a plugin drops it`() {
        val viewModel = LibraryViewModel(repository)
        viewModel.install("a", ByteArray(0))
        viewModel.uninstall("a")
        assertTrue(viewModel.state.value.plugins.isEmpty())

        repository.failWith = "no maps"
        viewModel.install("b", ByteArray(0))
        assertEquals(ImportStatus.Failed("no maps"), viewModel.state.value.status)
    }
}
