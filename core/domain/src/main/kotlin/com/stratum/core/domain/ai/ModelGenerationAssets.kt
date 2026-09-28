package com.stratum.core.domain.ai

import com.stratum.core.domain.content.ModelDefinition

/**
 * A generated model kept on the device: what was asked for, and what it has
 * been given to do in the game.
 *
 * The bindings are what turn a file into content. A model bound to a prop
 * block stands in the world wherever that block does; one bound to a monster
 * gives it a body. They become ordinary [ModelDefinition]s through
 * [toDefinition], so a model forged on the phone and a model shipped in a
 * plugin reach the renderer by the same road.
 */
data class ModelAsset(
    val id: String,
    val name: String,
    val prompt: String = "",
    val kind: ModelSubjectKind = ModelSubjectKind.PROP,
    val format: ModelFormat = ModelFormat.GLB,
    /** Which provider made it, for the player's reference. */
    val provider: String = "",
    val heightBlocks: Float = ModelDefinition.DEFAULT_HEIGHT,
    val triangleCount: Int = 0,
    /** A prop block drawn as this model; null when it dresses none. */
    val propBlockId: String? = null,
    /** A monster drawn with this model's baked sprite. */
    val enemyId: String? = null,
    /** The id of the blueprint made from it, when it has been voxelised. */
    val blueprintId: String? = null,
) {
    /** The pack-level reference this asset stands for, or null when it dresses nothing. */
    fun toDefinition(): ModelDefinition? =
        if (propBlockId == null && enemyId == null) null
        else ModelDefinition(
            id = "forged:$id",
            source = ModelDefinition.ASSET_PREFIX + id,
            name = name,
            height = heightBlocks.coerceIn(MIN_HEIGHT, ModelDefinition.MAX_HEIGHT),
            blockId = propBlockId,
            enemyId = enemyId,
        )

    private companion object {
        const val MIN_HEIGHT = 0.1f
    }
}
