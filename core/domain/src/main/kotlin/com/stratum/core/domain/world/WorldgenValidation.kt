package com.stratum.core.domain.world

/**
 * The world generator's data, checked at load.
 *
 * An ore naming a block nobody defined, or a dungeon naming a monster that
 * does not exist, would otherwise surface far from its cause: a crash deep in
 * generation, or a boss room nobody ever fights in. Which passes and carvers
 * exist is the engine's to say, so those are checked when the world is built,
 * where the message can list the ones it knows.
 */
object WorldgenValidation {

    fun problems(
        recipe: TerrainRecipe,
        structures: List<StructureTemplate>,
        knownBlock: (String) -> Boolean,
        biomeIds: Set<String>,
        enemyIds: Set<String>,
    ): List<String> = recipeProblems(recipe, knownBlock, biomeIds) +
        structures.flatMap { structureProblems(it, knownBlock, biomeIds, enemyIds) }

    private fun recipeProblems(recipe: TerrainRecipe, knownBlock: (String) -> Boolean, biomeIds: Set<String>): List<String> {
        fun unknownBlocks(what: String, ids: List<String>) = ids.filterNot(knownBlock).map { "terrain $what unknown block '$it'" }
        fun unknownBiomes(what: String, ids: List<String>) = ids.filter { it !in biomeIds }.map { "terrain $what unknown biome '$it'" }
        return recipe.ores.flatMap { unknownBlocks("ore uses", listOf(it.blockId) + it.replaces) + unknownBiomes("ore is limited to", it.biomeIds) } +
            recipe.trees.flatMap { unknownBlocks("tree uses", listOf(it.trunkBlockId, it.leafBlockId)) + unknownBiomes("tree is limited to", it.biomeIds) } +
            recipe.liquids.flatMap { unknownBlocks("liquid fills with", listOf(it.blockId)) } +
            recipe.climate?.points.orEmpty().flatMap { unknownBiomes("climate places", listOf(it.biomeId)) } +
            recipe.carvers.filter { it.maxZ >= Chunk.HEIGHT }.map { "terrain carver '${it.kind}' reaches above the world at ${it.maxZ}" } +
            recipe.ores.filter { it.maxZ >= Chunk.HEIGHT }.map { "terrain ore '${it.blockId}' reaches above the world at ${it.maxZ}" }
    }

    private fun structureProblems(
        template: StructureTemplate,
        knownBlock: (String) -> Boolean,
        biomeIds: Set<String>,
        enemyIds: Set<String>,
    ): List<String> {
        val id = template.id
        val markerRefs = template.pieces.flatMap { it.palette.values }.filter(StructurePiece::isMarker).mapNotNull(::markerRef)
        val enemies = template.dungeon?.let { it.enemyIds + listOfNotNull(it.bossId) }.orEmpty() +
            template.pieces.flatMap { it.palette.values }.filter { it.startsWith("@enemy:") || it.startsWith("@boss:") }.mapNotNull(::markerRef)
        return template.problems() +
            template.referencedBlockIds().filterNot(knownBlock).map { "structure '$id' uses unknown block '$it'" } +
            template.placement.biomeIds.filter { it !in biomeIds }.map { "structure '$id' is placed in unknown biome '$it'" } +
            enemies.distinct().filter { it !in enemyIds }.map { "structure '$id' spawns unknown enemy '$it'" } +
            template.pieces.flatMap { it.palette.values }.filter(StructurePiece::isMarker).map { it.substringBefore(':') }
                .filter { it !in MARKER_TOKENS }.distinct().map { "structure '$id' uses unknown marker '$it'" } +
            if (markerRefs.any(String::isBlank)) listOf("structure '$id' has a marker with an empty reference") else emptyList()
    }

    private fun markerRef(entry: String): String? = entry.substringAfter(':', "").takeIf { entry.contains(':') }

    /** The marker tokens a piece's palette may use. */
    val MARKER_TOKENS = setOf(StructurePiece.AIR, "@enemy", "@boss", "@loot", "@poi", "@entrance")
}
