package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition

/** Where one role's step stands. */
enum class StepStatus(val label: String) {
    WAITING("Waiting"),
    WORKING("Working"),
    REVIEW("Awaiting approval"),
    DONE("Done"),
    FAILED("Failed"),
    SKIPPED("Skipped"),
}

/**
 * One exchange with the model, kept whole: exactly what was asked, exactly
 * what came back, what the checks made of it and what was decided. Nothing
 * the studio does is hidden from the person running it.
 */
data class AgentAttempt(
    val number: Int,
    val systemPrompt: String,
    val userPrompt: String,
    val reply: String? = null,
    /** Why the reply was not accepted, in the words fed back on the next try. */
    val problems: List<String> = emptyList(),
    /** Ids this attempt added or revised, by section. */
    val added: Map<String, List<String>> = emptyMap(),
    val durationMillis: Long = 0,
    /** What a reviewer said, when one sent it back. */
    val reviewNote: String? = null,
) {
    val accepted: Boolean get() = reply != null && problems.isEmpty() && reviewNote == null
}

/** One role's part of a run. */
data class StudioStep(
    val role: AgentRoleDefinition,
    val status: StepStatus = StepStatus.WAITING,
    val attempts: List<AgentAttempt> = emptyList(),
    /** Why it failed or was skipped. */
    val reason: String? = null,
) {
    val latest: AgentAttempt? get() = attempts.lastOrNull()
}

/** A whole run as it happens: every step, and the draft pack as it stands. */
data class StudioJournal(
    val brief: String,
    val steps: List<StudioStep>,
    val draftJson: String = "",
    val problems: List<String> = emptyList(),
) {
    val finished: Boolean get() = steps.none { it.status == StepStatus.WAITING || it.status == StepStatus.WORKING || it.status == StepStatus.REVIEW }
    val fraction: Float get() = if (steps.isEmpty()) 1f else steps.count { it.status in SETTLED }.toFloat() / steps.size

    fun step(roleId: String): StudioStep? = steps.firstOrNull { it.role.id == roleId }

    internal fun with(step: StudioStep): StudioJournal = copy(steps = steps.map { if (it.role.id == step.role.id) step else it })

    /**
     * The run as a document: brief, every step, every prompt and reply.
     * Shareable, diffable, and the answer to "why did it write that".
     */
    fun toMarkdown(): String = buildString {
        appendLine("# Studio run")
        appendLine()
        appendLine("> $brief")
        problems.forEach { appendLine("- ⚠ $it") }
        steps.forEach { step ->
            appendLine()
            appendLine("## ${step.role.glyph} ${step.role.name} — ${step.status.label}")
            step.reason?.let { appendLine("_${it}_") }
            step.attempts.forEach { attempt ->
                appendLine()
                appendLine("### Attempt ${attempt.number} (${attempt.durationMillis} ms)")
                appendLine("**Asked:**")
                appendLine("```")
                appendLine(attempt.userPrompt)
                appendLine("```")
                attempt.reply?.let {
                    appendLine("**Replied:**")
                    appendLine("```json")
                    appendLine(it)
                    appendLine("```")
                }
                attempt.added.forEach { (section, ids) -> appendLine("- wrote $section: ${ids.joinToString()}") }
                attempt.problems.forEach { appendLine("- ✗ $it") }
                attempt.reviewNote?.let { appendLine("- ↺ reviewer: $it") }
            }
        }
    }

    private companion object {
        val SETTLED = setOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.SKIPPED)
    }
}

/** What a person decides about a step that asked for approval. */
sealed interface Review {
    data object Approve : Review
    /** Try again, with this note fed to the agent as the thing to fix. */
    data class Revise(val note: String) : Review
    /** Leave this role's work out; roles depending on it are skipped too. */
    data object Skip : Review
}

/** The person in the loop. Suspends until they decide; a test or an unattended run approves everything. */
fun interface ApprovalGate {
    suspend fun review(step: StudioStep, fragment: String): Review

    companion object {
        val ApproveAll = ApprovalGate { _, _ -> Review.Approve }
    }
}
