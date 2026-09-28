package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.CrewPlan
import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.creation.JobReporter

/**
 * Reads a one-role run's journal into job steps: what the checks made of each
 * reply, and what the repair mended.
 *
 * The model call itself is reported by the provider through the job's own
 * observer ("Waiting for the model"); this adds what only the pipeline knows,
 * once each attempt is settled: "Repairing 2 fields", then "Checking the reply"
 * passed, or failed with the first problem and a note that it is asking again.
 */
class ForgeJobReport(private val job: JobReporter) {

    private var seen = 0

    fun onJournal(journal: StudioJournal) {
        val step = journal.steps.firstOrNull() ?: return
        val attempts = step.attempts
        if (attempts.size < seen) seen = attempts.size
        attempts.drop(seen).forEach { attempt -> report(attempt, last = attempt.number >= step.role.maxAttempts) }
        seen = attempts.size
    }

    private fun report(attempt: AgentAttempt, last: Boolean) {
        // No reply means the call itself failed, which the provider already reported.
        if (attempt.reply == null) return
        if (attempt.repairs.isNotEmpty()) {
            job.begin(repairLabel(attempt.repairs.size), attempt.repairs.first())
            job.done(attempt.repairs.take(2).joinToString("; ") + if (attempt.repairs.size > 2) " and ${attempt.repairs.size - 2} more" else "")
        }
        job.begin(CHECKING)
        if (attempt.problems.isEmpty()) {
            val wrote = attempt.added.values.sumOf { it.size }
            job.done(if (wrote > 0) "$wrote ${if (wrote == 1) "thing" else "things"}, and it loads" else "it loads")
        } else {
            val count = attempt.problems.size
            job.stepFailed("$count ${if (count == 1) "problem" else "problems"}: ${attempt.problems.first()}" + if (last) "" else " -- asking again")
        }
    }

    companion object {
        const val CHECKING = "Checking the reply"

        fun repairLabel(count: Int) = "Repairing $count ${if (count == 1) "field" else "fields"}"
    }
}

/**
 * Reads a crew's journal into job steps, one per role, in the order they run:
 * which attempt a role is on, when it waits for the person, what it wrote, why
 * it failed or was skipped.
 *
 * A crew run is minutes of calls, so each role is one step rather than each
 * call; [observer] still puts the wait for the model into the role's detail.
 */
class CrewJobReport(private val job: JobReporter, private val modelName: String? = null) {

    private var active: String? = null
    private val settled = mutableSetOf<String>()
    private val attemptsSeen = mutableMapOf<String, Int>()

    /** What the observer writes into the running role's detail line. */
    val observer: GenerationObserver = object : GenerationObserver {
        override fun onStage(stage: GenerationStage) {
            when (stage) {
                GenerationStage.SENDING, GenerationStage.WAITING -> job.detail("${attemptPrefix()}waiting for ${modelName ?: "the model"}")
                GenerationStage.READING -> job.detail("${attemptPrefix()}reading the reply")
                else -> Unit
            }
        }

        override fun onAttempt(attempt: GenerationAttempt) {
            if (!attempt.succeeded) job.detail("${attemptPrefix()}${attempt.failure ?: "the model did not answer"}")
        }
    }

    private var currentAttempt = 1

    private fun attemptPrefix(): String = if (currentAttempt > 1) "try $currentAttempt: " else ""

    fun onJournal(journal: StudioJournal) {
        journal.steps.forEach { step ->
            val id = step.role.id
            if (id in settled) return@forEach
            val label = labelOf(step.role)
            when (step.status) {
                StepStatus.WAITING -> Unit
                StepStatus.WORKING -> {
                    if (active != id) { job.begin(label, "writing"); active = id }
                    val tried = step.attempts.size
                    if (tried > (attemptsSeen[id] ?: 0)) {
                        attemptsSeen[id] = tried
                        currentAttempt = tried + 1
                        val problems = step.latest?.problems.orEmpty()
                        job.detail(
                            step.latest?.reviewNote?.let { "revising: $it" }
                                ?: problems.firstOrNull()?.let { "${problems.size} to fix, asking again: $it" }
                                ?: "asking again",
                        )
                    } else if (tried == 0) {
                        currentAttempt = 1
                    }
                }
                StepStatus.REVIEW -> {
                    if (active != id) { job.begin(label); active = id }
                    job.detail("waiting for your approval")
                }
                StepStatus.DONE -> settle(id, label) { job.done(wrote(step)) }
                StepStatus.FAILED -> settle(id, label) { job.stepFailed(step.reason ?: "failed") }
                StepStatus.SKIPPED -> settle(id, label) { job.stepFailed("skipped: ${step.reason ?: "not needed"}") }
            }
        }
    }

    private inline fun settle(id: String, label: String, close: () -> Unit) {
        if (active != id) job.begin(label)
        close()
        active = null
        settled += id
    }

    private fun wrote(step: StudioStep): String {
        val added = step.attempts.lastOrNull()?.added.orEmpty().filterValues { it.isNotEmpty() }
        return added.entries.joinToString(", ") { (section, ids) -> "${ids.size} $section" }.ifBlank { "done" }
    }

    companion object {
        fun labelOf(role: AgentRoleDefinition): String = "${role.glyph} ${role.name}"

        /** The steps a crew will go through, in the order it will run them, to show before the first one starts. */
        fun plannedSteps(crew: List<AgentRoleDefinition>): List<String> =
            ((CrewPlan.of(crew) as? CrewPlan.Ordered)?.roles ?: crew).map(::labelOf)
    }
}
