package com.stratum.core.domain.item

import com.stratum.core.domain.stats.StatModifier

/**
 * The checks that turn a bad item reference into an error at load time
 * rather than a unique that never drops or a resistance to nothing.
 */
object ItemValidation {

    fun problems(catalogue: ItemCatalogue, inserts: List<InsertDefinition>, damageTypeIds: Set<String>): List<String> {
        fun unknown(typeId: String?) = typeId != null && typeId !in damageTypeIds
        fun typesOf(modifiers: List<StatModifier>) = modifiers.mapNotNull { it.damageTypeId }
        fun typesIn(ranges: List<ModifierRange>) = ranges.mapNotNull { it.damageTypeId }

        // Grown rungs repeat their root's references; reporting each rung again would bury the one line to fix.
        val authored = catalogue.bases.filter { it.family == null }
        return authored.filter { unknown(it.weapon?.damageTypeId) }
            .map { "weapon '${it.id}' uses unknown damage type '${it.weapon?.damageTypeId}'" } +
            authored.flatMap { base ->
                (typesOf(base.defences) + typesIn(base.implicits)).filter(::unknown)
                    .map { "base '${base.id}' names unknown damage type '$it'" }
            } +
            catalogue.affixes.flatMap { affix ->
                affix.tiers.flatMap { typesIn(it.modifiers) }.distinct().filter(::unknown)
                    .map { "affix '${affix.id}' resists unknown damage type '$it'" }
            } +
            inserts.flatMap { insert ->
                (typesOf(insert.modifiers) + listOfNotNull(insert.convertsToDamageTypeId)).distinct().filter(::unknown)
                    .map { "insert '${insert.id}' names unknown damage type '$it'" }
            } +
            catalogue.uniques.flatMap { uniqueProblems(it, catalogue, ::unknown) } +
            catalogue.sets.flatMap { setProblems(it, catalogue, ::unknown) } +
            catalogue.namePools.filter { it.first.isEmpty() || it.second.isEmpty() }
                .map { "name pool '${it.id}' needs words in both halves" }
    }

    private fun uniqueProblems(unique: UniqueDefinition, catalogue: ItemCatalogue, unknown: (String?) -> Boolean): List<String> =
        listOfNotNull(
            "unique '${unique.id}' is made on unknown base '${unique.baseId}'".takeIf { catalogue.base(unique.baseId) == null },
            "unique '${unique.id}' belongs to unknown set '${unique.setId}'".takeIf { unique.setId != null && catalogue.set(unique.setId) == null },
        ) + (unique.modifiers + unique.localModifiers).mapNotNull { it.damageTypeId }.distinct().filter(unknown)
            .map { "unique '${unique.id}' names unknown damage type '$it'" }

    private fun setProblems(set: ItemSetDefinition, catalogue: ItemCatalogue, unknown: (String?) -> Boolean): List<String> {
        val pieces = catalogue.piecesOf(set.id).size
        return listOfNotNull("set '${set.id}' has no pieces".takeIf { pieces == 0 }) +
            set.bonuses.filter { pieces in 1 until it.pieces }
                .map { "set '${set.id}' has a ${it.pieces}-piece bonus but only $pieces pieces" } +
            set.bonuses.flatMap { bonus -> bonus.modifiers.mapNotNull { it.damageTypeId } }.distinct().filter(unknown)
                .map { "set '${set.id}' names unknown damage type '$it'" }
    }
}
