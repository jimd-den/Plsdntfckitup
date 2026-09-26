package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.CrewPlan
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.PackOrigin
import kotlinx.serialization.json.JsonObject

/** How a run ended: the pack when it assembled, and the journal either way. */
data class StudioOutcome(val pack: ContentPack?, val journal: StudioJournal)

/**
 * The studio: a crew of agents that writes a content pack the way a small
 * team would, each role in turn, each building on the others' work.
 *
 * Every step is checked as a plugin author's would be -- the draft must read
 * as plugin JSON and assemble on the packs it sits on -- and a step that
 * fails is retried with the problems fed back. A role that asks for approval
 * waits for a person. Nothing happens off the record: every prompt, reply,
 * problem and decision goes into the [StudioJournal], which [onProgress]
 * sees after every change.
 */
class StudioPipeline(
    private val model: LanguageModelPort,
    base: List<ContentPack>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val check = DraftCheck(base)

    suspend fun run(
        brief: StudioBrief,
        crew: List<AgentRoleDefinition>,
        gate: ApprovalGate = ApprovalGate.ApproveAll,
        onProgress: (StudioJournal) -> Unit = {},
    ): StudioOutcome {
        val plan = CrewPlan.of(crew)
        if (plan is CrewPlan.Invalid) return StudioOutcome(null, StudioJournal(brief.prompt, emptyList(), problems = plan.problems)).also { onProgress(it.journal) }
        val roles = (plan as CrewPlan.Ordered).roles
        val unknown = roles.flatMap { r -> r.sections.filter { it !in PackSections.known }.map { "agent role '${r.id}' writes unknown section '$it'" } }
        if (unknown.isNotEmpty()) return StudioOutcome(null, StudioJournal(brief.prompt, emptyList(), problems = unknown)).also { onProgress(it.journal) }

        var draft = PackSections.empty(brief.packId, brief.packName, brief.author)
        var journal = StudioJournal(brief.prompt, roles.map { StudioStep(it) }, PackSections.render(draft))
        fun report(updated: StudioJournal) { journal = updated; onProgress(updated) }
        report(journal)

        for (role in roles) {
            val blockedBy = role.dependsOn.firstOrNull { dep -> journal.step(dep)?.status != StepStatus.DONE }
            if (blockedBy != null) {
                report(journal.with(journal.step(role.id)!!.copy(status = StepStatus.SKIPPED, reason = "needs ${journal.step(blockedBy)?.role?.name ?: blockedBy}, which did not finish")))
                continue
            }
            val (step, fragment) = work(role, brief, draft, journal, gate) { report(it) }
            if (step.status == StepStatus.DONE && fragment != null) {
                draft = PackSections.merge(draft, fragment, role.sections)
                report(journal.with(step).copy(draftJson = PackSections.render(draft)))
            } else {
                report(journal.with(step))
            }
        }

        val (pack, problems) = check.decode(draft)
        val finished = pack?.copy(origin = PackOrigin.AI_GENERATED, description = pack.description.ifBlank { brief.prompt })
        return StudioOutcome(finished.takeIf { problems.isEmpty() }, journal.copy(problems = problems)).also { onProgress(it.journal) }
    }

    /**
     * One role's step: ask, check, retry with what was wrong, and -- when the
     * role asks for it -- wait for a person. Returns the settled step and the
     * fragment it produced.
     */
    private suspend fun work(
        role: AgentRoleDefinition,
        brief: StudioBrief,
        draft: JsonObject,
        start: StudioJournal,
        gate: ApprovalGate,
        report: (StudioJournal) -> Unit,
    ): Pair<StudioStep, JsonObject?> {
        var journal = start
        var step = StudioStep(role, StepStatus.WORKING)
        fun update(next: StudioStep) { step = next; journal = journal.with(next); report(journal) }
        update(step)

        var feedback = emptyList<String>()
        var tries = 0
        while (tries < role.maxAttempts) {
            tries++
            val request = PromptComposer.compose(role, brief, draft, check.baseJson, feedback)
            val started = clock()
            val attempt = AgentAttempt(step.attempts.size + 1, request.systemPrompt, request.userPrompt)
            val reply = model.complete(request).getOrElse { failure ->
                update(step.copy(attempts = step.attempts + attempt.copy(problems = listOf("the model could not be reached: ${failure.message}"), durationMillis = clock() - started)))
                return step.copy(status = StepStatus.FAILED, reason = "the model could not be reached") to null
            }
            val parsed = runCatching { PackSections.parse(extractObject(reply)) }
            val fragment = parsed.getOrNull()
            val problems = if (fragment == null) listOf("the reply was not a JSON object: ${parsed.exceptionOrNull()?.message}")
            else check.problems(draft, fragment, role.sections, brief.packId)
            val done = attempt.copy(
                reply = fragment?.let(PackSections::render) ?: reply,
                problems = problems,
                added = fragment?.let { PackSections.added(it, role.sections) }.orEmpty(),
                durationMillis = clock() - started,
            )
            update(step.copy(attempts = step.attempts + done))
            if (problems.isNotEmpty()) { feedback = problems; continue }
            if (!role.requiresApproval) return step.copy(status = StepStatus.DONE) to fragment

            update(step.copy(status = StepStatus.REVIEW))
            when (val review = gate.review(step, done.reply.orEmpty())) {
                Review.Approve -> return step.copy(status = StepStatus.DONE) to fragment
                Review.Skip -> return step.copy(status = StepStatus.SKIPPED, reason = "skipped by the reviewer") to null
                is Review.Revise -> {
                    update(step.copy(status = StepStatus.WORKING, attempts = step.attempts.dropLast(1) + done.copy(reviewNote = review.note)))
                    feedback = listOf("the reviewer asks: ${review.note}")
                    // A revision the person asked for does not use up the agent's own tries.
                    tries--
                }
            }
        }
        return step.copy(status = StepStatus.FAILED, reason = "still wrong after ${role.maxAttempts} tries") to null
    }

    private fun extractObject(reply: String): String {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in the reply" }
        return reply.substring(start, end + 1)
    }
}
