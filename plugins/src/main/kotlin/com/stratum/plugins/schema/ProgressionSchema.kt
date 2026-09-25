package com.stratum.plugins.schema

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.passive.PassiveKind
import com.stratum.core.domain.passive.PassiveLink
import com.stratum.core.domain.passive.PassiveNode
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlinx.serialization.Serializable

/**
 * One modifier, spelled the way a tooltip reads:
 * `{ "stat": "damage", "kind": "increased", "value": 0.1 }` is 10% increased damage.
 */
@Serializable
internal data class ModifierSchema(
    val stat: String,
    val kind: String = SchemaValues.name(ModifierKind.INCREASED),
    val value: Float,
    val damageType: String? = null,
) {
    fun toDomain(owner: String) = StatModifier(
        SchemaValues.enum<Stat>(stat, "$owner stat"), SchemaValues.enum<ModifierKind>(kind, "$owner modifier kind"), value, damageType,
    )

    companion object {
        fun of(m: StatModifier) = ModifierSchema(SchemaValues.name(m.stat), SchemaValues.name(m.kind), m.value, m.damageTypeId)
    }
}

@Serializable
internal data class PassiveNodeSchema(
    val id: String,
    val name: String,
    val kind: String = SchemaValues.name(PassiveKind.SMALL),
    val modifiers: List<ModifierSchema> = emptyList(),
    val x: Float = 0f,
    val y: Float = 0f,
    val classes: List<String> = emptyList(),
    val description: String = "",
) {
    fun toDomain() = PassiveNode(
        id, name, SchemaValues.enum<PassiveKind>(kind, "passive '$id' kind"), modifiers.map { it.toDomain("passive '$id'") },
        x, y, classes, description,
    )

    companion object {
        fun of(n: PassiveNode) = PassiveNodeSchema(
            n.id, n.name, SchemaValues.name(n.kind), n.modifiers.map(ModifierSchema::of), n.x, n.y, n.classIds, n.description,
        )
    }
}

/** A tree: its nodes, and its links as `["from", "to"]` pairs. */
@Serializable
internal data class PassiveTreeSchema(
    val id: String,
    val name: String,
    val nodes: List<PassiveNodeSchema> = emptyList(),
    val links: List<List<String>> = emptyList(),
) {
    fun toDomain() = PassiveTree(id, name, nodes.map { it.toDomain() }, links.map(::link))

    private fun link(pair: List<String>): PassiveLink {
        if (pair.size != 2) throw ImportException("passive tree '$id': a link is two node ids, not $pair")
        return PassiveLink(pair[0], pair[1])
    }

    companion object {
        fun of(t: PassiveTree) = PassiveTreeSchema(t.id, t.name, t.nodes.map(PassiveNodeSchema::of), t.links.map { listOf(it.from, it.to) })
    }
}
