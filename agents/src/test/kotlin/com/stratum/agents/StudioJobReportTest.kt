package com.stratum.agents

import com.stratum.agents.forge.ContentForge
import com.stratum.agents.forge.ForgeContext
import com.stratum.agents.forge.ForgeKind
import com.stratum.agents.forge.ForgeOrder
import com.stratum.agents.forge.ForgePlaceholder
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.creation.InMemoryJobCenter
import com.stratum.core.domain.creation.JobStatus
import com.stratum.core.domain.creation.observer
import com.stratum.core.domain.item.PowerTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Replies in turn and reports its stages the way a provider adapter does. */
private class StagedModel(vararg replies: String) : LanguageModelPort {
    private val queue = ArrayDeque(replies.toList())

    override suspend fun complete(request: CompletionRequest, observer: GenerationObserver): Result<String> {
        observer.onStage(GenerationStage.PREPARING)
        observer.onStage(GenerationStage.SENDING)
        observer.onStage(GenerationStage.READING)
        observer.onStage(GenerationStage.DONE)
        return Result.success(if (queue.size > 1) queue.removeFirst() else queue.first())
    }
}

private val ring = """
    { "uniques": [{ "name": "Kiln Heart", "base": "bronze ring", "modifiers": [{ "stat": "damage", "kind": "more", "value": 4 }],
                    "flags": ["skills_cost_health", "god_mode"] }],
      "lore": [{ "title": "The Kiln", "body": "It burned for a hundred years. Then it was worn." }] }
""".trimIndent()

@OptIn(ExperimentalCoroutinesApi::class)
class StudioJobReportTest {

    @Test
    fun `a forge run reads as the call, the repair and the check, retry included`() = runTest {
        val jobs = InMemoryJobCenter(CoroutineScope(StandardTestDispatcher(testScheduler)), clock = { testScheduler.currentTime })
        val forge = ContentForge(StagedModel("not json at all", ring), listOf(IgboContentPack.pack), clock = { 0L })
        jobs.launch("gear", "Kiln Heart") {
            val report = ForgeJobReport(this)
            forge.forge(ForgeOrder.Unique("a ring"), ForgeContext(budget = PowerTier.BROKEN), onProgress = report::onJournal, observer = observer(model = "writer"))
            "Kiln Heart"
        }
        advanceUntilIdle()

        val steps = jobs.jobs.value.single().steps
        assertEquals(
            listOf(
                "Writing the request", "Waiting for writer", "Reading the reply", "Checking the reply",
                "Writing the request again (try 2)", "Waiting for writer", "Reading the reply",
            ),
            steps.take(7).map { it.label },
        )
        assertEquals(JobStatus.FAILED, steps[3].status)
        assertTrue("asking again" in steps[3].detail.orEmpty(), "${steps[3]}")
        val repair = steps[7]
        assertTrue(repair.label.startsWith("Repairing ") && repair.label.endsWith(" fields"), repair.label)
        assertEquals(ForgeJobReport.CHECKING, steps[8].label)
        assertEquals(JobStatus.DONE, steps[8].status)
        assertEquals(JobStatus.DONE, jobs.jobs.value.single().status)
    }

    @Test
    fun `a crew run is one step per role, in the order the roles run`() = runTest {
        val lore = AgentRoleDefinition("t:lore", "Loremaster", "📜", sections = listOf("factions"))
        val land = AgentRoleDefinition("t:land", "Cartographer", "🗺", sections = listOf("biomes"))
        val beasts = AgentRoleDefinition("t:beasts", "Bestiary", "🐺", sections = listOf("enemies"), dependsOn = listOf(lore.id))
        val crew = listOf(beasts, lore, land)
        val planned = CrewJobReport.plannedSteps(crew)
        assertEquals("🐺 Bestiary", planned.last(), "a role runs after what it depends on")

        val jobs = InMemoryJobCenter(CoroutineScope(StandardTestDispatcher(testScheduler)), clock = { testScheduler.currentTime })
        jobs.launch("world", "Hive Siege", steps = planned) {
            val report = CrewJobReport(this)
            fun attempt(n: Int, problems: List<String> = emptyList(), added: Map<String, List<String>> = emptyMap()) =
                AgentAttempt(n, "", "", reply = "{}", problems = problems, added = added)
            // The journal lists roles in the order the pipeline runs them.
            val waiting = listOf(lore, land, beasts).map { StudioStep(it) }
            fun journal(vararg steps: StudioStep) = StudioJournal("brief", waiting.map { w -> steps.firstOrNull { it.role.id == w.role.id } ?: w })
            report.onJournal(journal(StudioStep(lore, StepStatus.WORKING)))
            report.onJournal(journal(StudioStep(lore, StepStatus.WORKING, listOf(attempt(1, problems = listOf("faction 'x' has no name"))))))
            report.onJournal(journal(StudioStep(lore, StepStatus.DONE, listOf(attempt(1), attempt(2, added = mapOf("factions" to listOf("a", "b")))))))
            report.onJournal(journal(StudioStep(lore, StepStatus.DONE), StudioStep(land, StepStatus.REVIEW, listOf(attempt(1)))))
            report.onJournal(journal(StudioStep(lore, StepStatus.DONE), StudioStep(land, StepStatus.FAILED, reason = "still wrong after 3 tries"), StudioStep(beasts, StepStatus.SKIPPED, reason = "needs Cartographer")))
            null
        }
        advanceUntilIdle()
        val steps = jobs.jobs.value.single().steps
        assertEquals(planned, steps.map { it.label })
        assertEquals(listOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.FAILED), steps.map { it.status })
        assertEquals("2 factions", steps[0].detail)
        assertEquals("still wrong after 3 tries", steps[1].detail)
        assertEquals("skipped: needs Cartographer", steps[2].detail)
    }

    @Test
    fun `a placeholder is named from the prompt before the model answers`() {
        assertEquals("Heavy Bronze Cleaver", ForgePlaceholder.nameFrom("A heavy bronze cleaver cast for executions"))
        assertEquals("River Spirit", ForgePlaceholder.nameFrom("The river spirit who takes a toll from every ferry"))
        assertEquals("Ring", ForgePlaceholder.nameFrom("a ring that makes every skill cost blood"))
        val card = ForgePlaceholder.of(ForgeKind.LORE, "  ")
        assertEquals("Untitled lore", card.title)
        assertEquals(ForgePlaceholder.SECTION, card.section)
    }
}
