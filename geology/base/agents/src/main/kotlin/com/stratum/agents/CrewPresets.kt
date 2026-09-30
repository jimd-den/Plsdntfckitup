package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition

/** A crew the studio offers ready-made: who is on it and what they make between them. */
data class CrewPreset(
    val id: String,
    val name: String,
    val description: String,
    val roles: List<AgentRoleDefinition>,
)

/**
 * The crews the studio can start from. "A whole world" is what the old
 * world generator made -- land, peoples, monsters, towns, food, lore --
 * now written by the crew on the same pipeline, so there is one way AI
 * writes content and one record of how it did.
 */
object CrewPresets {

    const val WORLD = "${StandardCrew.NS}:world"
    const val PACK = "${StandardCrew.NS}:pack"

    val world = CrewPreset(
        id = WORLD,
        name = "A whole world",
        description = "Land, regions and blocks; factions and their lore; monsters, towns, food, outposts and the rules it plays by.",
        roles = StandardCrew.all,
    )

    /** The presets for content whose packs bring [packRoles]: their crew first, when they bring one. */
    fun available(packRoles: List<AgentRoleDefinition>): List<CrewPreset> = listOfNotNull(
        packRoles.takeIf { it.isNotEmpty() }?.let {
            CrewPreset(PACK, "The loaded packs' crew", "The roles your plugins bring, with their own briefs.", it)
        },
        world,
    )
}
