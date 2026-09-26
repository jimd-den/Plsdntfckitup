package com.stratum.agents

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.CrewPlan
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.plugins.schema.PackJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val igbo = IgboContentPack.pack

/** Plugin JSON for a pack holding only what [build] puts in it. */
private fun fragment(build: ContentPack.() -> ContentPack): String = PackJson.encode(ContentPack(id = "test", name = "Test", author = "").build())

private val guild = igbo.factions.first().copy(id = "test:ash_guild", name = "Ash Guild", relations = emptyMap())
private val ashStone = igbo.blocks.first { it.isSolid }.copy(id = "test:ash_stone", displayName = "Ash Stone")
private val ashland = igbo.biomes.first().copy(id = "test:ashland", name = "Ashland")
private val ghoul = igbo.enemies.first().copy(id = "test:ghoul", name = "Ghoul", factionId = guild.id, spawnBiomeIds = listOf(ashland.id))

private val lore = AgentRoleDefinition("t:lore", "Loremaster", sections = listOf("factions", "lore"))
private val land = AgentRoleDefinition("t:land", "Cartographer", sections = listOf("blocks", "biomes"))
private val beasts = AgentRoleDefinition("t:beasts", "Bestiary", sections = listOf("enemies"), dependsOn = listOf(lore.id, land.id))
private val crew = listOf(beasts, lore, land)

/** Answers each role from its own queue of replies, and remembers what it was asked. */
private class ScriptedModel(replies: Map<String, List<String>>) : LanguageModelPort {
    private val queues = replies.mapValues { ArrayDeque(it.value) }
    val asked = mutableListOf<CompletionRequest>()

    override suspend fun complete(request: CompletionRequest, observer: GenerationObserver): Result<String> {
        asked += request
        val role = queues.keys.first { request.systemPrompt.contains("You are the $it ") }
        return Result.success(queues.getValue(role).removeFirst())
    }
}

private val good = mapOf(
    "Loremaster" to listOf(fragment { copy(factions = listOf(guild)) }),
    "Cartographer" to listOf(fragment { copy(blocks = listOf(ashStone), biomes = listOf(ashland)) }),
    "Bestiary" to listOf(fragment { copy(enemies = listOf(ghoul)) }),
)

private val brief = StudioBrief(prompt = "an ash-choked guild city", packId = "test", packName = "Ash")

class StudioPipelineTest {

    @Test
    fun `a crew is put in dependency order, and a circle is refused`() {
        val ordered = assertIs<CrewPlan.Ordered>(CrewPlan.of(crew))
        assertEquals(listOf(lore.id, land.id, beasts.id), ordered.roles.map { it.id })

        val circle = CrewPlan.of(listOf(lore.copy(dependsOn = listOf(beasts.id)), land, beasts))
        assertIs<CrewPlan.Invalid>(circle)
        assertIs<CrewPlan.Invalid>(CrewPlan.of(listOf(beasts)))
    }

    @Test
    fun `the standard crew is in order and writes only sections that exist`() {
        assertIs<CrewPlan.Ordered>(CrewPlan.of(StandardCrew.all))
        assertTrue(StandardCrew.all.flatMap { it.sections }.all { it in PackSections.known })
    }

    @Test
    fun `every section the plugin format writes is one agents may write`() {
        val full = PackSections.parse(PackJson.encode(igbo))
        assertTrue(full.keys.filter { it !in setOf("id", "name", "author", "version", "description", "sheets", "maps") }.all { it in PackSections.known }, "${full.keys - PackSections.known}")
    }

    @Test
    fun `a crew writes a pack that loads, each role building on the last`() = runTest {
        val model = ScriptedModel(good)
        val outcome = StudioPipeline(model, listOf(igbo), clock = { 0L }).run(brief, crew)

        val pack = assertNotNull(outcome.pack, "problems: ${outcome.journal.problems} ${outcome.journal.steps.map { it.latest?.problems }}")
        assertEquals(listOf(guild.id), pack.factions.map { it.id })
        assertEquals(listOf(ghoul.id), pack.enemies.map { it.id })
        assertTrue(outcome.journal.steps.all { it.status == StepStatus.DONE })
        assertEquals(1f, outcome.journal.fraction)
        // The bestiary was told what the loremaster and cartographer made.
        val bestiaryPrompt = model.asked.last().userPrompt
        assertTrue(guild.id in bestiaryPrompt && ashland.id in bestiaryPrompt)
    }

    @Test
    fun `a rejected reply is retried with the problems fed back`() = runTest {
        val lost = ghoul.copy(factionId = "test:nobody")
        val model = ScriptedModel(good + ("Bestiary" to listOf(fragment { copy(enemies = listOf(lost)) }, fragment { copy(enemies = listOf(ghoul)) })))
        val outcome = StudioPipeline(model, listOf(igbo), clock = { 0L }).run(brief, crew)

        val step = outcome.journal.step(beasts.id)!!
        assertEquals(StepStatus.DONE, step.status)
        assertEquals(2, step.attempts.size)
        assertTrue(step.attempts.first().problems.any { "test:nobody" in it }, "${step.attempts.first().problems}")
        assertTrue("REJECTED" in step.attempts.last().userPrompt && "test:nobody" in step.attempts.last().userPrompt)
        assertNotNull(outcome.pack)
    }

    @Test
    fun `writing outside its sections, or ids outside the pack, is a problem`() = runTest {
        val trespass = fragment { copy(factions = listOf(guild), blocks = listOf(ashStone)) }
        val foreign = fragment { copy(factions = listOf(guild.copy(id = "elsewhere:guild"))) }
        val model = ScriptedModel(good + ("Loremaster" to listOf(trespass, foreign, "not json at all")))
        val outcome = StudioPipeline(model, listOf(igbo), clock = { 0L }).run(brief, crew)

        val step = outcome.journal.step(lore.id)!!
        assertEquals(StepStatus.FAILED, step.status)
        assertTrue(step.attempts[0].problems.any { "'blocks'" in it })
        assertTrue(step.attempts[1].problems.any { "must start with 'test:'" in it })
        assertTrue(step.attempts[2].problems.any { "JSON" in it })
        assertEquals(StepStatus.SKIPPED, outcome.journal.step(beasts.id)!!.status, "the bestiary needs the factions")
    }

    @Test
    fun `a reviewer can send work back with a note, or skip it`() = runTest {
        val gated = crew.map { if (it.id == lore.id) it.copy(requiresApproval = true) else it }
        val model = ScriptedModel(good + ("Loremaster" to List(2) { fragment { copy(factions = listOf(guild)) } }))
        var reviews = 0
        val gate = ApprovalGate { _, _ -> if (reviews++ == 0) Review.Revise("darker, please") else Review.Approve }
        val outcome = StudioPipeline(model, listOf(igbo), clock = { 0L }).run(brief, gated, gate)

        val step = outcome.journal.step(lore.id)!!
        assertEquals(StepStatus.DONE, step.status)
        assertEquals("darker, please", step.attempts.first().reviewNote)
        assertTrue("darker, please" in step.attempts.last().userPrompt)

        val skipped = StudioPipeline(ScriptedModel(good), listOf(igbo), clock = { 0L }).run(brief, gated, ApprovalGate { _, _ -> Review.Skip })
        assertEquals(StepStatus.SKIPPED, skipped.journal.step(lore.id)!!.status)
        assertEquals(StepStatus.SKIPPED, skipped.journal.step(beasts.id)!!.status)
        assertNull(skipped.journal.step(land.id)!!.reason)
    }

    @Test
    fun `the journal is the whole record, prompts and replies included`() = runTest {
        val seen = mutableListOf<StudioJournal>()
        val outcome = StudioPipeline(ScriptedModel(good), listOf(igbo), clock = { 0L }).run(brief, crew) { seen += it }

        assertTrue(seen.size > crew.size, "progress was reported as it happened")
        assertTrue(seen.any { j -> j.steps.any { it.status == StepStatus.WORKING } })
        val record = outcome.journal.toMarkdown()
        assertTrue(brief.prompt in record && guild.id in record && "Attempt 1" in record)
    }

    @Test
    fun `a pack can bring its own crew`() {
        val chapter = AgentRoleDefinition("w40:chaplain", "Chaplain", sections = listOf("factions", "units"), brief = "Chapters, heresy, and faith.")
        val decoded = PackJson.decode(PackJson.encode(igbo.copy(agentRoles = listOf(chapter))))
        assertEquals(listOf(chapter), decoded.agentRoles)
    }
}
