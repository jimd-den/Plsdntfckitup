package com.stratum.app.shell

import com.stratum.agents.CrewJobReport
import com.stratum.agents.CrewPresets
import com.stratum.agents.StudioBrief
import com.stratum.agents.StudioPipeline
import com.stratum.app.CreationJobs
import com.stratum.core.domain.content.ContentPack
import com.stratum.feature.forge.CrewViewModel

/**
 * Starts the world crew on [prompt] as a background job, for a world that is
 * already being played from the same sentence.
 *
 * No approval gates: the player asked for a world and went to play it, so
 * nobody is waiting at the crew screen to approve each role. Every step is
 * still on the job's timeline, and the pack is handed to [deliver], which
 * holds it until the player leaves the world it was written for.
 *
 * Returns false, and starts nothing, when no model is connected: the instant
 * world is then the whole world, which is what "instant by default" promises.
 */
internal fun queueWorldCrew(
    app: AppViewModel,
    prompt: String,
    name: String,
    deliver: (ContentPack) -> Unit,
): Boolean {
    val ai = app.graph.ai
    if (!ai.isConfigured() || prompt.isBlank()) return false
    val crew = CrewPresets.world.roles
    val base = app.game.content.value.packs
    val brief = StudioBrief(prompt = prompt.trim(), packId = CrewViewModel.namespaceOf(name), packName = name)
    val model = ai.settings.load().model.takeIf { it.isNotBlank() }
    CreationJobs.center.launch(
        kind = "world",
        title = name,
        steps = CrewJobReport.plannedSteps(crew) + CHECKING,
    ) {
        val report = CrewJobReport(this, model)
        val outcome = StudioPipeline(ai.languageModel, base).run(brief, crew, observer = report.observer) { report.onJournal(it) }
        begin(CHECKING)
        val pack = outcome.pack ?: fail("The crew could not finish a pack that loads: ${outcome.journal.problems.firstOrNull() ?: "a role did not finish"}.")
        deliver(pack)
        done("${pack.biomes.size} regions, ${pack.enemies.size} monsters, ${pack.factions.size} factions")
        "${pack.name} is ready. It joins your world the next time you enter it."
    }
    return true
}

private const val CHECKING = "Checking the whole pack"
