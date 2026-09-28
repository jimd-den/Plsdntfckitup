package com.stratum.feature.forge

import com.stratum.agents.CrewPreset
import com.stratum.agents.CrewPresets
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.creation.InstantWorld
import com.stratum.core.domain.creation.JobStatus
import com.stratum.core.domain.creation.PackDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers every role with one faction, once the test lets it. */
private class HeldModel : LanguageModelPort {
    val release = CompletableDeferred<Unit>()

    override suspend fun complete(request: CompletionRequest, observer: GenerationObserver): Result<String> {
        release.await()
        return Result.success("""{ "factions": [{ "id": "hive_siege:guild", "name": "Ash Guild" }] }""")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CrewViewModelTest {

    private val loremaster = AgentRoleDefinition("t:lore", "Loremaster", "📜", sections = listOf("factions"))
    private val world = CrewPreset(CrewPresets.WORLD, "A whole world", "", listOf(loremaster))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a world is played at once while the crew writes, and its pack waits for the next entry`() {
        val model = HeldModel()
        val played = mutableListOf<InstantWorld>()
        val delivered = mutableListOf<ContentPack>()
        val vm = CrewViewModel(
            model, { listOf(IgboContentPack.pack) }, listOf(world), { true }, onInstall = {}, initialPreset = CrewPresets.WORLD,
            onPlayNow = { played += it },
            deliver = { pack -> delivered += pack; PackDelivery.HELD },
        )
        vm.updatePrompt("A hive city under siege")
        vm.updateName("Hive Siege")
        assertTrue(vm.state.value.offersInstantWorld)

        vm.playNow()
        assertEquals("Hive Siege", played.single().name, "the world is playable before the model has answered")
        val running = assertNotNull(vm.state.value.job)
        assertEquals(JobStatus.RUNNING, running.status)
        assertEquals("📜 Loremaster", running.currentStep?.label)
        assertTrue(delivered.isEmpty())

        model.release.complete(Unit)
        val job = assertNotNull(vm.state.value.job)
        assertEquals(JobStatus.DONE, job.status, "${job.summary}")
        assertEquals("hive_siege", delivered.single().id)
        assertEquals(PackDelivery.HELD, vm.state.value.delivery)
        assertTrue("next time you enter" in job.summary.orEmpty(), job.summary)
    }

    @Test
    fun `a crew that is not played along the way is offered, not installed, and can be stopped`() {
        val model = HeldModel()
        val delivered = mutableListOf<ContentPack>()
        val vm = CrewViewModel(model, { listOf(IgboContentPack.pack) }, listOf(world), { true }, onInstall = {}, deliver = { delivered += it; PackDelivery.INSTALLED })
        vm.updatePrompt("A hive city")
        vm.start()
        model.release.complete(Unit)
        assertNotNull(vm.state.value.result)
        assertTrue(delivered.isEmpty())
        assertNull(vm.state.value.delivery)

        val stuck = HeldModel()
        val again = CrewViewModel(stuck, { listOf(IgboContentPack.pack) }, listOf(world), { true }, onInstall = {})
        again.updatePrompt("A hive city")
        again.start()
        again.cancel()
        assertEquals(JobStatus.CANCELLED, again.state.value.job?.status)
        assertEquals("Stopped.", again.state.value.error)
    }
}
