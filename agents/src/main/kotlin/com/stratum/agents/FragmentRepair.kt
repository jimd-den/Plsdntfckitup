package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition
import kotlinx.serialization.json.JsonObject

/**
 * What a repair made of a reply: the fragment to check, what it changed, and
 * what it could not save.
 */
data class Repaired(
    val fragment: JsonObject,
    /** Every change, worded for the journal: "stat 'life' read as max_health". */
    val notes: List<String> = emptyList(),
    /**
     * What could not be repaired, worded for the agent that has to fix it.
     * Non-empty means the attempt is rejected and retried with these.
     */
    val rejections: List<String> = emptyList(),
)

/**
 * Mends a reply before it is checked. Models forget namespaces, write "20%"
 * where the format wants 0.2 and name a damage type that nearly exists;
 * sending those back costs a round trip for something a rule can fix. What a
 * rule cannot fix is rejected with a reason, and every change is on the
 * record, so a repair never silently decides what the model meant.
 */
fun interface FragmentRepair {
    fun repair(fragment: JsonObject, role: AgentRoleDefinition, brief: StudioBrief): Repaired

    companion object {
        /** Takes the reply as written: the crew's standing behaviour. */
        val None = FragmentRepair { fragment, _, _ -> Repaired(fragment) }
    }
}
