package com.stratum.feature.forge

import com.stratum.agents.CrewPresets
import com.stratum.agents.forge.Creations
import com.stratum.agents.forge.ForgeField
import com.stratum.agents.forge.ForgeKind
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val igbo = IgboContentPack.pack

private class FakeModel(private val reply: String) : LanguageModelPort {
    var calls = 0

    override suspend fun complete(request: CompletionRequest, observer: GenerationObserver): Result<String> {
        calls++
        return Result.success(reply)
    }
}

private val unique = """
    ```json
    { "uniques": [{ "name": "Kiln Heart", "base": "bronze ring", "modifiers": [{ "stat": "damage", "kind": "more", "value": 4 }],
                    "flags": ["skills_cost_health", "hits_ignore_resistance"] }],
      "lore": [{ "title": "The Kiln", "body": "It burned for a hundred years. Then it was worn." }] }
    ```
""".trimIndent()

@OptIn(ExperimentalCoroutinesApi::class)
class ContentForgeViewModelTest {

    private var saved: ContentPack? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        saved = null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(model: LanguageModelPort = FakeModel(unique), configured: Boolean = true) = ContentForgeViewModel(
        model = model,
        base = { listOf(igbo) },
        isProviderConfigured = { configured },
        creations = { saved },
        saveCreations = { saved = it },
        clock = { 0L },
    )

    @Test
    fun `a request is read back as cards with their power`() {
        val vm = viewModel()
        vm.selectKind(ForgeKind.UNIQUE)
        vm.updatePrompt("a kiln that became a ring")
        vm.selectBudget(PowerTier.BROKEN)
        vm.forge()

        val state = vm.state.value
        assertFalse(state.running)
        assertNull(state.error, state.error)
        val card = state.cards.first { it.section == "uniques" }
        assertEquals("Kiln Heart", card.title)
        assertEquals(PowerTier.BROKEN, card.power?.tier)
        assertTrue(card.lines.any { "more damage" in it }, "${card.lines}")
        assertTrue(state.cards.any { it.section == "lore" })
        assertTrue(state.repairs.isNotEmpty(), "the fenced reply's missing namespace was repaired, on the record")
    }

    @Test
    fun `a balanced request comes back balanced`() {
        val vm = viewModel()
        vm.updatePrompt("a ring")
        vm.forge()
        assertEquals(PowerTier.BALANCED, vm.state.value.cards.first { it.section == "uniques" }.power?.tier)
    }

    @Test
    fun `nothing is asked for without a prompt or a provider`() {
        val model = FakeModel(unique)
        val vm = viewModel(model)
        vm.forge()
        assertNotNull(vm.state.value.error)

        val offline = viewModel(model, configured = false)
        offline.updatePrompt("a ring")
        offline.forge()
        assertFalse(offline.state.value.providerConfigured)
        assertEquals(0, model.calls)
    }

    @Test
    fun `a reply that cannot be repaired says why`() {
        val vm = viewModel(FakeModel("I would rather not."))
        vm.updatePrompt("a ring")
        vm.forge()
        val error = assertNotNull(vm.state.value.error)
        assertTrue("could not make" in error, error)
        assertTrue(vm.state.value.cards.isEmpty())
    }

    @Test
    fun `a card's name and text can be rewritten before keeping it`() {
        val vm = viewModel()
        vm.updatePrompt("a ring")
        vm.forge()
        val id = vm.state.value.cards.first { it.section == "uniques" }.id
        vm.startEdit(id, ForgeField.NAME)
        assertEquals("Kiln Heart", vm.state.value.editing?.text)
        vm.updateEdit("Heart of the Last Kiln")
        vm.commitEdit()
        vm.startEdit(id, ForgeField.TEXT)
        vm.updateEdit("Still warm.")
        vm.commitEdit()

        val card = vm.state.value.cards.first { it.id == id }
        assertEquals("Heart of the Last Kiln", card.title)
        assertEquals("Still warm.", card.text)
        assertNull(vm.state.value.editing)
    }

    @Test
    fun `keeping a result grows one plugin, and keeping again adds beside it`() {
        val vm = viewModel()
        vm.updatePrompt("a ring")
        vm.forge()
        vm.addToCreations()
        val first = assertNotNull(saved)
        assertEquals(Creations.ID, first.id)
        assertEquals(1, first.uniques.size)
        assertTrue(vm.state.value.added)
        assertEquals(2, vm.state.value.kept, "the unique and its lore")

        vm.addToCreations()
        assertEquals(1, saved!!.uniques.size, "the same result is kept once")

        vm.regenerate()
        vm.addToCreations()
        assertEquals(2, saved!!.uniques.size, "a second forging is a second thing, not a replacement")
        assertEquals(2, saved!!.uniques.map { it.id }.distinct().size)
    }

    @Test
    fun `the kind decides which slots and ladders are offered`() {
        val vm = viewModel()
        vm.selectKind(ForgeKind.ARMOUR)
        assertTrue(ItemSlot.WEAPON !in vm.state.value.slots && vm.state.value.offersLadder)
        vm.selectSlot(ItemSlot.HELM)
        vm.toggleLadder()
        vm.selectKind(ForgeKind.LORE)
        assertNull(vm.state.value.slot)
        assertFalse(vm.state.value.ladder)
        vm.setLevels(30, 10)
        assertEquals(30..30, vm.state.value.levelFrom..vm.state.value.levelTo, "a range cannot run backwards")
    }

    @Test
    fun `the world generator is a crew preset, selected on the way in`() {
        val presets = CrewPresets.available(emptyList())
        val crew = CrewViewModel(FakeModel(unique), { listOf(igbo) }, presets, { true }, {}, initialPreset = CrewPresets.WORLD)
        assertEquals(CrewPresets.WORLD, crew.state.value.presetId)
        assertEquals(CrewPresets.world.roles, crew.state.value.crew)
    }
}
