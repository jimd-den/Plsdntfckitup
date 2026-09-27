package com.stratum.core.domain.item

/**
 * Everything items are made from, as the generator sees it: every base the
 * packs wrote plus the ladders grown from them, and the affixes, uniques,
 * sets and name pools that go on top.
 */
data class ItemCatalogue(
    val bases: List<ItemBase>,
    val affixes: List<AffixDefinition> = emptyList(),
    val uniques: List<UniqueDefinition> = emptyList(),
    val sets: List<ItemSetDefinition> = emptyList(),
    val namePools: List<ItemNamePool> = emptyList(),
) {
    private val basesById = bases.associateBy { it.id }
    private val uniquesById = uniques.associateBy { it.id }
    private val setsById = sets.associateBy { it.id }
    private val affixesById = affixes.associateBy { it.id }

    fun base(id: String): ItemBase? = basesById[id]

    fun unique(id: String): UniqueDefinition? = uniquesById[id]

    fun set(id: String): ItemSetDefinition? = setsById[id]

    fun affix(id: String): AffixDefinition? = affixesById[id]

    /** The pieces of a set, in the order the pack listed them. */
    fun piecesOf(setId: String): List<UniqueDefinition> = uniques.filter { it.setId == setId }

    companion object {
        /**
         * A catalogue over a pack's own [bases], grown into ladders by
         * [tiers] -- the pack's, or the standard numerals when it names none.
         */
        fun of(
            bases: List<ItemBase>,
            affixes: List<AffixDefinition> = emptyList(),
            uniques: List<UniqueDefinition> = emptyList(),
            sets: List<ItemSetDefinition> = emptyList(),
            namePools: List<ItemNamePool> = emptyList(),
            tiers: List<BaseTier> = emptyList(),
        ) = ItemCatalogue(BaseFamilies.grow(bases, tiers.ifEmpty { BaseFamilies.standard }), affixes, uniques, sets, namePools)
    }
}
