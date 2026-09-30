package com.stratum.plugins.schema

import com.stratum.core.domain.ai.AgentRoleDefinition
import kotlinx.serialization.Serializable

private val ROLE = AgentRoleDefinition(id = "", name = "", sections = listOf("lore"))

/** A studio agent a pack brings: which sections it writes, after whom, and what it is told. */
@Serializable
internal data class AgentRoleSchema(
    val id: String,
    val name: String,
    val glyph: String = ROLE.glyph,
    val description: String = "",
    val sections: List<String>,
    val dependsOn: List<String> = emptyList(),
    val brief: String = "",
    val requiresApproval: Boolean = ROLE.requiresApproval,
    val maxAttempts: Int = ROLE.maxAttempts,
    val temperature: Float = ROLE.temperature,
) {
    fun toDomain() = AgentRoleDefinition(id, name, glyph, description, sections, dependsOn, brief, requiresApproval, maxAttempts, temperature)

    companion object {
        fun of(r: AgentRoleDefinition) = AgentRoleSchema(r.id, r.name, r.glyph, r.description, r.sections, r.dependsOn, r.brief, r.requiresApproval, r.maxAttempts, r.temperature)
    }
}
