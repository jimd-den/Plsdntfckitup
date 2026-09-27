package com.stratum.plugins.schema

import com.stratum.core.domain.content.ModelDefinition
import kotlinx.serialization.Serializable

/**
 * A 3D model a pack dresses its content with.
 *
 * The targets are spelled as plain ids rather than a list of typed
 * references, because that is how a person writes them by hand: a statue
 * that stands in for the `shrine` block says `"block": "mypack:shrine"`.
 */
@Serializable
internal data class ModelSchema(
    val id: String,
    val source: String,
    val name: String = "",
    val height: Float = ModelDefinition.DEFAULT_HEIGHT,
    val block: String? = null,
    val structure: String? = null,
    val weapon: String? = null,
    val enemy: String? = null,
) {
    fun toDomain() = ModelDefinition(id, source, name, height, block, structure, weapon, enemy)

    companion object {
        fun of(m: ModelDefinition) = ModelSchema(m.id, m.source, m.name, m.height, m.blockId, m.structureId, m.weaponId, m.enemyId)
    }
}
