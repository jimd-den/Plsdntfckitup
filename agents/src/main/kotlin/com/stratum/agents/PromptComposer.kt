package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.CompletionRequest
import kotlinx.serialization.json.JsonObject

/** What the person asked for, and what the studio builds on. */
data class StudioBrief(
    /** The world, in their words: "grimdark hive city under siege by rot cults". */
    val prompt: String,
    val packId: String,
    val packName: String,
    val author: String = "Studio",
    val modelId: String? = null,
)

/**
 * Writes the prompts. Pure: the same role, brief, draft and problems always
 * ask the same thing, so a journal can be replayed and a prompt can be tested.
 */
object PromptComposer {

    /** Sections whose ids other sections point at, listed so agents reference what exists instead of inventing it. */
    val referenced = listOf(
        "blocks", "biomes", "factions", "enemies", "damageTypes", "skills", "weapons",
        "consumables", "needs", "resources", "structures", "units",
    )

    fun compose(
        role: AgentRoleDefinition,
        brief: StudioBrief,
        draft: JsonObject,
        base: List<JsonObject> = emptyList(),
        problems: List<String> = emptyList(),
    ): CompletionRequest = CompletionRequest(
        systemPrompt = system(role, brief),
        userPrompt = user(role, brief, draft, base, problems),
        temperature = role.temperature,
        modelId = brief.modelId,
    )

    private fun system(role: AgentRoleDefinition, brief: StudioBrief): String = buildString {
        appendLine("You are the ${role.name} on a small team building a content pack for an isometric voxel action RPG.")
        if (role.description.isNotBlank()) appendLine(role.description)
        if (role.brief.isNotBlank()) appendLine(role.brief)
        appendLine()
        appendLine("Reply with ONE JSON object and nothing else: no prose, no markdown fences.")
        appendLine("It may contain only these keys: ${role.sections.joinToString()}.")
        appendLine("Every new id must start with \"${brief.packId}:\".")
        appendLine("Reference only ids that exist in the lists you are given, or that you define in this reply.")
        appendLine("Omit any field whose default is fine; the examples show every field that matters.")
    }

    private fun user(role: AgentRoleDefinition, brief: StudioBrief, draft: JsonObject, base: List<JsonObject>, problems: List<String>): String = buildString {
        appendLine("THE WORLD: ${brief.prompt}")
        appendLine("PACK: ${brief.packName} (${brief.packId})")
        appendLine()
        appendLine("WRITE: ${role.sections.joinToString()}")
        role.sections.forEach { section ->
            val example = (listOf(draft) + base).firstNotNullOfOrNull { PackSections.example(it, section) }
            if (example != null) {
                appendLine()
                appendLine("One \"$section\" entry, as a pattern (copy its shape, not its content):")
                appendLine(example)
            }
        }
        val known = knownIds(base, draft)
        if (known.isNotEmpty()) {
            appendLine()
            appendLine("IDS THAT EXIST:")
            known.forEach { (section, ids) -> appendLine("$section: ${ids.joinToString()}") }
        }
        val written = role.sections.associateWith { PackSections.ids(draft, it) }.filterValues { it.isNotEmpty() }
        if (written.isNotEmpty()) {
            appendLine()
            appendLine("ALREADY IN THE DRAFT (revise by reusing an id, or add new ones):")
            written.forEach { (section, ids) -> appendLine("$section: ${ids.joinToString()}") }
        }
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("YOUR LAST REPLY WAS REJECTED. Fix exactly these and reply again in full:")
            problems.forEach { appendLine("- $it") }
        }
    }

    /** Referenced ids from the base packs (their entries are patterns and their ids fair game) and the draft, by section. */
    fun knownIds(base: List<JsonObject>, draft: JsonObject): Map<String, List<String>> =
        referenced.associateWith { section -> (base + draft).flatMap { PackSections.ids(it, section) }.distinct() }
            .filterValues { it.isNotEmpty() }
}
