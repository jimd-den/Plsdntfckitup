package com.stratum.agents.forge

import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import kotlinx.serialization.json.JsonObject

/**
 * Reads codex entries. An entry must say something -- a title and a body --
 * and whatever it claims to be about must exist, in the loaded packs or in
 * the same reply; a subject that does not is matched to what it nearly
 * named, or let go, since lore about nothing still reads, and lore pointing
 * at nothing would never surface.
 */
internal class LoreReader(
    private val vocabulary: ForgeVocabulary,
    private val namespace: String,
    private val numbers: ModifierReader,
    private val log: RepairLog,
) {

    /**
     * [subjects] are the ids this reply defines, which lore may be about as
     * much as anything already loaded; [names] finds a subject by its name.
     */
    fun entry(obj: JsonObject, fallback: LoreCategory, subjects: Collection<String>, names: (String) -> String?): LoreEntry? {
        val title = Lenient.string(obj, "title", "name", "heading")
        val body = Lenient.string(obj, "body", "text", "content", "description", "entry")
        if (title == null || body == null) {
            log.note("dropped a lore entry with no ${if (title == null) "title" else "body"}")
            return null
        }
        val id = numbers.id(obj, "lore")
        val written = Lenient.string(obj, "category", "kind", "type")
        val category = ForgeWords.loreCategory(written) ?: fallback.also {
            if (written != null) log.note("lore '$id': '$written' is not a category; filed under ${it.name.lowercase()}")
        }
        val candidates = vocabulary.subjectIds + subjects
        val subject = Lenient.string(obj, "subject", "subjectId", "about")?.let { raw ->
            Lenient.match(raw, candidates, namespace, names).also {
                if (it == null) log.note("lore '$id': subject '$raw' does not exist; it stands on its own")
                else if (it != raw) log.note("lore '$id': subject '$raw' read as '$it'")
            }
        }
        return LoreEntry(id = id, title = title, body = body, category = category, subjectId = subject)
    }
}
