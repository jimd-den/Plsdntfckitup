package com.stratum.agents.forge

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.PackOrigin

/**
 * The player's own plugin: everything they kept from the forge, in one
 * pack that grows, rather than one plugin per generation cluttering the
 * library. It installs, loads, disables and shares like any other.
 */
object Creations {

    const val ID = "user.creations"
    const val NAME = "My creations"

    fun empty(author: String = "A Stratum player"): ContentPack = ContentPack(
        id = ID, name = NAME, author = author, origin = PackOrigin.AI_GENERATED,
        description = "Lore and gear made in the content forge.",
    )

    /** What [append] made: the grown pack, and the ids the new things ended up with. */
    data class Appended(val pack: ContentPack, val added: List<String>)

    /**
     * [fragment] laid onto [existing]. A new thing whose id the pack already
     * uses is renamed rather than overwriting what was kept before -- two
     * forged blades both called "ember edge" are two blades -- and every
     * reference inside the fragment follows the rename. The result must
     * assemble on [base], or nothing changes.
     */
    fun append(existing: ContentPack?, fragment: ContentPack, base: List<ContentPack>): Result<Appended> = runCatching {
        val into = existing ?: empty()
        val taken = idsOf(into).toMutableSet()
        val renames = LinkedHashMap<String, String>()
        idsOf(fragment).forEach { id ->
            val local = if (id.startsWith("$ID:")) id else "$ID:${id.substringAfter(':')}"
            var candidate = local
            var n = 2
            while (candidate in taken) candidate = "${local}_${n++}"
            taken += candidate
            if (candidate != id) renames[id] = candidate
        }
        val renamed = rename(fragment, renames)
        val grown = into.copy(
            itemBases = into.itemBases + renamed.itemBases,
            affixes = into.affixes + renamed.affixes,
            uniques = into.uniques + renamed.uniques,
            itemSets = into.itemSets + renamed.itemSets,
            loreEntries = into.loreEntries + renamed.loreEntries,
        )
        ContentPackAssembler().assemble(base.filterNot { it.id == ID } + grown)
        Appended(grown, idsOf(renamed).toList())
    }

    /** [pack] with [creations] folded in, for sharing both as one plugin. */
    fun combine(pack: ContentPack, creations: ContentPack?): ContentPack = if (creations == null) pack else pack.copy(
        itemBases = pack.itemBases + creations.itemBases,
        affixes = pack.affixes + creations.affixes,
        uniques = pack.uniques + creations.uniques,
        itemSets = pack.itemSets + creations.itemSets,
        loreEntries = pack.loreEntries + creations.loreEntries,
    )

    /** Removes one kept thing, and the lore that was about it. */
    fun remove(pack: ContentPack, id: String): ContentPack = pack.copy(
        itemBases = pack.itemBases.filterNot { it.id == id },
        affixes = pack.affixes.filterNot { it.id == id },
        uniques = pack.uniques.filterNot { it.id == id }.map { if (it.setId == id) it.copy(setId = null) else it },
        itemSets = pack.itemSets.filterNot { it.id == id },
        loreEntries = pack.loreEntries.filterNot { it.id == id || it.subjectId == id },
    )

    private fun idsOf(pack: ContentPack): LinkedHashSet<String> = LinkedHashSet<String>().apply {
        pack.itemBases.mapTo(this) { it.id }
        pack.affixes.mapTo(this) { it.id }
        pack.uniques.mapTo(this) { it.id }
        pack.itemSets.mapTo(this) { it.id }
        pack.loreEntries.mapTo(this) { it.id }
    }

    private fun rename(pack: ContentPack, renames: Map<String, String>): ContentPack {
        if (renames.isEmpty()) return pack
        fun r(id: String) = renames[id] ?: id
        return pack.copy(
            itemBases = pack.itemBases.map { it.copy(id = r(it.id)) },
            affixes = pack.affixes.map { it.copy(id = r(it.id), group = r(it.group)) },
            uniques = pack.uniques.map { it.copy(id = r(it.id), baseId = r(it.baseId), setId = it.setId?.let(::r)) },
            itemSets = pack.itemSets.map { it.copy(id = r(it.id)) },
            loreEntries = pack.loreEntries.map { it.copy(id = r(it.id), subjectId = it.subjectId?.let(::r)) },
        )
    }
}
