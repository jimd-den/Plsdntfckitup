package com.stratum.agents

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.plugins.schema.PackJson
import kotlinx.serialization.json.JsonObject

/**
 * Whether a draft is a pack the game would load: it must read as plugin JSON
 * and assemble on top of the packs it builds on, with every reference
 * resolved. The same checks a hand-written plugin faces, and the problems are
 * worded for the agent that has to fix them.
 */
class DraftCheck(private val base: List<ContentPack>, private val assembler: ContentPackAssembler = ContentPackAssembler()) {

    /** The draft decoded, or the reasons it cannot be. */
    fun decode(draft: JsonObject): Pair<ContentPack?, List<String>> = try {
        val pack = PackJson.decode(PackSections.render(draft), source = "draft")
        pack to (runCatching { assembler.assemble(base + pack) }.exceptionOrNull()?.let(::linesOf).orEmpty())
    } catch (failure: Exception) {
        null to linesOf(failure)
    }

    /** Everything wrong with laying [fragment] from [sections] over [draft]. */
    fun problems(draft: JsonObject, fragment: JsonObject, sections: List<String>, packId: String): List<String> {
        val trespass = PackSections.trespass(fragment, sections).map { "you may only write ${sections.joinToString()}, not '$it'" }
        val empty = if (sections.none { fragment.containsKey(it) }) listOf("the reply contained none of ${sections.joinToString()}") else emptyList()
        val foreign = sections.flatMap { section ->
            PackSections.ids(fragment, section).filter { !it.startsWith("$packId:") && it !in baseIds(section) }
                .map { "$section id '$it' must start with '$packId:'" }
        }
        val merged = PackSections.merge(draft, fragment, sections)
        return trespass + empty + foreign + decode(merged).second
    }

    private fun baseIds(section: String): Set<String> = baseJson.flatMapTo(HashSet()) { PackSections.ids(it, section) }

    /** The base packs as plugin JSON, for prompts and id checks. */
    val baseJson: List<JsonObject> by lazy { base.map { PackSections.parse(PackJson.encode(it)) } }

    private fun linesOf(failure: Throwable): List<String> =
        (failure.message ?: failure.javaClass.simpleName).split("; ", "\n").map { it.trim().removePrefix("- ") }.filter { it.isNotBlank() }.take(MAX_PROBLEMS)

    private companion object {
        const val MAX_PROBLEMS = 12
    }
}
