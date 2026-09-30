package com.stratum.plugins.schema

import com.stratum.core.domain.strategy.ResourceDefinition
import com.stratum.core.domain.strategy.StructureDefinition
import com.stratum.core.domain.strategy.UnitDefinition
import com.stratum.core.domain.world.BlockMaterial
import kotlinx.serialization.Serializable

private val RESOURCE = ResourceDefinition(id = "", name = "")
private val STRUCTURE = StructureDefinition(id = "", name = "")
private val UNIT = UnitDefinition(id = "", name = "", actorId = "")

/** An outpost stockpile: `items` maps an item or block id to what depositing one gives; `materials` counts any block of that material. */
@Serializable
internal data class ResourceSchema(
    val id: String,
    val name: String,
    val glyph: String = RESOURCE.glyph,
    val color: String = SchemaValues.color(RESOURCE.color),
    val items: Map<String, Int> = emptyMap(),
    val materials: List<String> = emptyList(),
) {
    fun toDomain() = ResourceDefinition(
        id, name, glyph, SchemaValues.color(color, "resource '$id' color"), items,
        materials.mapTo(LinkedHashSet()) { SchemaValues.enum<BlockMaterial>(it, "resource '$id' material") },
    )

    companion object {
        fun of(r: ResourceDefinition) = ResourceSchema(r.id, r.name, r.glyph, SchemaValues.color(r.color), r.fromItems, r.fromMaterials.map(SchemaValues::name))
    }
}

/** Something an outpost builds. Rates are per minute; `maxCount` absent means no limit. */
@Serializable
internal data class StructureSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val glyph: String = STRUCTURE.glyph,
    val cost: Map<String, Int> = emptyMap(),
    val produces: Map<String, Float> = emptyMap(),
    val upkeep: Map<String, Float> = emptyMap(),
    val housing: Int = 0,
    val workers: Int = 0,
    val defense: Int = 0,
    val storage: Map<String, Int> = emptyMap(),
    val requires: List<String> = emptyList(),
    val maxCount: Int? = null,
) {
    fun toDomain() = StructureDefinition(
        id, name, description, glyph, cost, produces, upkeep, housing, workers, defense, storage, requires, maxCount ?: STRUCTURE.maxCount,
    )

    companion object {
        fun of(s: StructureDefinition) = StructureSchema(
            s.id, s.name, s.description, s.glyph, s.cost, s.produces, s.upkeep, s.housing, s.workers, s.defense, s.storage, s.requires,
            s.maxCount.takeIf { it != STRUCTURE.maxCount },
        )
    }
}

/** A soldier an outpost trains; `actor` is the enemy definition that walks the world for it. */
@Serializable
internal data class UnitSchema(
    val id: String,
    val name: String,
    val actor: String,
    val cost: Map<String, Int> = emptyMap(),
    val upkeep: Map<String, Float> = emptyMap(),
    val requires: String? = null,
    val defense: Int = UNIT.defense,
) {
    fun toDomain() = UnitDefinition(id, name, actor, cost, upkeep, requires, defense)

    companion object {
        fun of(u: UnitDefinition) = UnitSchema(u.id, u.name, u.actorId, u.cost, u.upkeep, u.requires, u.defense)
    }
}
